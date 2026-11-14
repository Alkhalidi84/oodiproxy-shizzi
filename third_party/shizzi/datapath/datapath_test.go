package datapath

import (
	"encoding/json"
	"sync"
	"testing"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const (
	testQueueDepth = 16
	testMTU        = 1500
)

// newTestStack mirrors the stack Start builds, over a channel endpoint rather
// than the fd-backed one, which needs a real TUN. Kept in step with Start by
// hand: the protocol set and route table below must match it.
func newTestStack(t *testing.T) *stack.Stack {
	t.Helper()

	netStack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
		},
	})
	t.Cleanup(netStack.Close)

	if err := netStack.CreateNIC(nicID, channel.New(testQueueDepth, testMTU, "")); err != nil {
		t.Fatalf("CreateNIC: %v", err)
	}
	netStack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	// The TUN's own addresses, which the framework assigns on the real device;
	// a route needs a source address on the NIC to be selectable.
	for _, address := range []tcpip.ProtocolAddress{tunAddress4(), tunAddress6()} {
		if err := netStack.AddProtocolAddress(nicID, address, stack.AddressProperties{}); err != nil {
			t.Fatalf("AddProtocolAddress %v: %v", address.AddressWithPrefix, err)
		}
	}
	return netStack
}

func tunAddress4() tcpip.ProtocolAddress {
	return tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{192, 0, 2, 2}).
			WithPrefix(),
	}
}

func tunAddress6() tcpip.ProtocolAddress {
	return tcpip.ProtocolAddress{
		Protocol: ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16([16]byte{
			0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
		}).WithPrefix(),
	}
}

// TestStackRoutesBothFamilies is the regression test for #5: an IPv6-addressed
// flow must find a route. Before IPv6 was registered, FindRoute returned
// ErrNetworkUnreachable and every v6 packet off the TUN was dropped.
func TestStackRoutesBothFamilies(t *testing.T) {
	netStack := newTestStack(t)
	netStack.SetPromiscuousMode(nicID, true)
	netStack.SetSpoofing(nicID, true)

	tests := []struct {
		name     string
		protocol tcpip.NetworkProtocolNumber
		address  tcpip.Address
	}{
		{
			name:     "IPv4",
			protocol: ipv4.ProtocolNumber,
			address:  tcpip.AddrFrom4([4]byte{192, 0, 2, 1}),
		},
		{
			name:     "IPv6",
			protocol: ipv6.ProtocolNumber,
			address: tcpip.AddrFrom16([16]byte{
				0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
			}),
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			route, err := netStack.FindRoute(nicID, tcpip.Address{}, test.address, test.protocol, false)
			if err != nil {
				t.Fatalf("FindRoute to %v: %v", test.address, err)
			}
			route.Release()
		})
	}
}

func TestClientCountersTrackDirectionsPerIP(t *testing.T) {
	counters := newClientCounters()
	counters.add("192.0.2.10", true, 120)
	counters.add("192.0.2.10", false, 450)
	counters.add("192.0.2.11", true, 7)

	got := counters.statsJSON(1)
	var decoded struct { Clients []clientTraffic `json:"clients"` }
	if err := json.Unmarshal([]byte(got), &decoded); err != nil {
		t.Fatalf("statsJSON returned invalid JSON: %v", err)
	}
	if len(decoded.Clients) != 2 { t.Fatalf("got %d clients, want 2", len(decoded.Clients)) }
	if decoded.Clients[0].IPAddress != "192.0.2.10" ||
		decoded.Clients[0].UploadBytes != 120 || decoded.Clients[0].DownloadBytes != 450 {
		t.Fatalf("unexpected first client counters: %+v", decoded.Clients[0])
	}
}

func TestClientCountersConcurrentUpdates(t *testing.T) {
	counters := newClientCounters()
	const workers, increments = 12, 100
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < increments; j++ { counters.add("192.0.2.20", true, 1) }
		}()
	}
	wg.Wait()
	var decoded struct { Clients []clientTraffic `json:"clients"` }
	if err := json.Unmarshal([]byte(counters.statsJSON(1)), &decoded); err != nil { t.Fatal(err) }
	if len(decoded.Clients) != 1 || decoded.Clients[0].UploadBytes != workers*increments {
		t.Fatalf("concurrent counter mismatch: %+v", decoded.Clients)
	}
}
