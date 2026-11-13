package datapath

import (
	"io"
	"net"
	"strconv"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	// maxInFlightTCP bounds half-open connections the forwarder will track.
	// Tethered clients are few; this exists to cap memory under a SYN flood
	// from a misbehaving client, not to shape normal traffic.
	maxInFlightTCP = 512

	// rcvWnd of 0 lets netstack pick its default receive window.
	defaultRcvWnd = 0

	// dialTimeout bounds how long a client waits for an unreachable host.
	dialTimeout = 10 * time.Second

	// udpFlowTimeout closes an idle UDP association. DNS exchanges finish in
	// milliseconds; QUIC keeps its own keepalives well inside this.
	udpFlowTimeout = 60 * time.Second
)

// installForwarders routes inbound flows to userspace handlers.
//
// Without these the stack silently drops every packet: nothing is listening on
// the addresses tethered clients dial, because those addresses belong to hosts
// out on the internet rather than to this stack.
func installForwarders(netStack *stack.Stack, dialer *net.Dialer, counters *clientCounters) {
	tcpForwarder := tcp.NewForwarder(netStack, defaultRcvWnd, maxInFlightTCP,
		func(request *tcp.ForwarderRequest) { forwardTCP(request, dialer, counters) })
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(netStack,
		func(request *udp.ForwarderRequest) bool { return forwardUDP(request, dialer, counters) })
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

// forwardTCP proxies one client connection to its intended destination.
//
// The upstream socket is dialed before the client endpoint is created, so a
// refused or unreachable destination sends the client a RST rather than
// completing a handshake that then dies.
func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer, counters *clientCounters) {
	id := request.ID()

	upstream, err := dialer.Dial("tcp", destinationOf(id))
	if err != nil {
		request.Complete(true)
		return
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		request.Complete(true)
		return
	}
	request.Complete(false)

	client := gonet.NewTCPConn(&queue, endpoint)
	go relayCounted(client, upstream, counters, addressString(id.RemoteAddress))
}

// forwardUDP proxies one client datagram flow to its destination.
//
// Returning false leaves the request unhandled, which makes the stack send the
// client an ICMP port unreachable — the right answer when the destination
// could not be reached, and better than dropping the datagram silently.
func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer, counters *clientCounters) bool {
	id := request.ID()

	upstream, err := dialer.Dial("udp", destinationOf(id))
	if err != nil {
		return false
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		upstream.Close()
		return false
	}

	client := gonet.NewUDPConn(&queue, endpoint)
	go relayDatagrams(client, upstream, counters, addressString(id.RemoteAddress))
	return true
}

func countedCopy(dst, src net.Conn, counters *clientCounters, clientIP string, upload bool) {
    count, _ := io.Copy(dst, src)
    if counters != nil { counters.add(clientIP, upload, count) }
}

// relay copies bytes both ways until either side finishes, then closes both.
//
// Half-close is deliberately not propagated: a client that shuts down its write
// side still expects to read the response, but tracking that per direction adds
// state this prototype does not need. Both sides close when either completes.
func relayCounted(client, upstream net.Conn, counters *clientCounters, clientIP string) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		countedCopy(upstream, client, counters, clientIP, true)
		done <- struct{}{}
	}()
	go func() {
		countedCopy(client, upstream, counters, clientIP, false)
		done <- struct{}{}
	}()

	<-done
}

// relayDatagrams copies datagrams both ways until the flow goes idle.
//
// Unlike TCP there is no close to observe, so an idle deadline is the only
// thing that reclaims the association.
func relayDatagrams(client, upstream net.Conn, counters *clientCounters, clientIP string) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)

	go func() {
		copyWithIdleTimeout(upstream, client, counters, clientIP, true)
		done <- struct{}{}
	}()
	go func() {
		copyWithIdleTimeout(client, upstream, counters, clientIP, false)
		done <- struct{}{}
	}()

	<-done
}

// copyWithIdleTimeout copies until either side errors or the flow goes idle.
func copyWithIdleTimeout(dst, src net.Conn, counters *clientCounters, clientIP string, upload bool) {
	buffer := make([]byte, maxDatagramSize)

	for {
		if err := src.SetReadDeadline(time.Now().Add(udpFlowTimeout)); err != nil {
			return
		}

		read, err := src.Read(buffer)
		if read > 0 {
			written, writeErr := dst.Write(buffer[:read])
			if written > 0 && counters != nil { counters.add(clientIP, upload, int64(written)) }
			if writeErr != nil || written != read { return }
		}
		if err != nil {
			return
		}
	}
}

// maxDatagramSize is large enough for any UDP payload a client can send
// through a 1500-byte-MTU link without fragmentation surprises.
const maxDatagramSize = 65535

// destinationOf renders the address a flow was actually addressed to.
//
// LocalAddress is the destination from the stack's perspective: the client
// dialed it, and this stack received it only because the NIC is promiscuous.
func destinationOf(id stack.TransportEndpointID) string {
	return net.JoinHostPort(
		addressString(id.LocalAddress),
		strconv.Itoa(int(id.LocalPort)),
	)
}

func addressString(address tcpip.Address) string {
	return net.IP(address.AsSlice()).String()
}
