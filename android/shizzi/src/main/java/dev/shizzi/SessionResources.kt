package dev.shizzi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.ParcelFileDescriptor
import dev.shizzi.gomobile.datapath.Datapath
import dev.shizzi.gomobile.datapath.Session as DatapathSession

class SessionResources(
    private val testNetworkApi: TestNetworkApi,
    private val connectivityManager: ConnectivityManager,
) {

    private var tun: TunHandle? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    // The Go/gVisor endpoint borrows its fd and requires it to stay open until
    // the endpoint has stopped. Keep a dedicated duplicate so the framework-
    // owned TestNetworkInterface descriptor is never handed to native code.
    private var datapathFileDescriptor: ParcelFileDescriptor? = null
    private var network: Network? = null
    private var datapathSession: DatapathSession? = null
    private var keepAliveCallback: ConnectivityManager.NetworkCallback? = null

    private val lifetimeToken = Binder()

    val interfaceName: String? get() = runCatching { tun?.interfaceName }.getOrNull()
    val acquiredNetwork: Network? get() = network

    fun acquire(
        addresses: List<android.net.LinkAddress>,
        dnsServers: List<java.net.InetAddress>,
        availabilityTimeoutMs: Int,
    ): String {
        val created = testNetworkApi.createTunInterface(addresses)
        tun = created
        fileDescriptor = created.fileDescriptor

        val name = created.interfaceName
        try {
            testNetworkApi.setupTestNetwork(name, dnsServers, lifetimeToken)
            network = awaitAvailability(name, availabilityTimeoutMs)
            requestKeepAlive()
            return name
        } catch (failure: Throwable) {
            // setupTestNetwork may have partially registered the interface before
            // throwing. Recover its Network handle if it became visible, then unwind
            // every resource that can safely be released.
            if (network == null) network = findNetworkOn(name)
            val cleanupProblems = release()
            if (cleanupProblems.isNotEmpty()) {
                failure.addSuppressed(
                    IllegalStateException("acquire cleanup incomplete: " + cleanupProblems.joinToString("; "))
                )
            }
            throw IllegalStateException(
                "acquire: failed to provision test network '$name': ${failure.message}",
                failure,
            )
        }
    }

    private fun requestKeepAlive() {
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(resolveTransportTest())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {}
        keepAliveCallback = callback

        runCatching { connectivityManager.requestNetwork(request, callback) }
            .onFailure { failure ->
                keepAliveCallback = null
                throw IllegalStateException(
                    "requestKeepAlive: could not request the test network, " +
                        "it would be lingered away within seconds",
                    failure,
                )
            }
    }

    fun startDatapath(mtu: Int) {
        val descriptor = fileDescriptor
            ?: error("startDatapath: no TUN fd; acquire() must succeed first")

        check(datapathSession == null) {
            "startDatapath: datapath is already attached"
        }

        require(mtu in MIN_MTU..MAX_MTU) {
            "startDatapath: invalid MTU $mtu (expected $MIN_MTU..$MAX_MTU)"
        }
        check(descriptor.fileDescriptor.valid()) {
            "startDatapath: framework TUN descriptor is already closed (fd=${descriptor.fd})"
        }
        val originalFd = descriptor.fd
        val nativeDescriptor = try {
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "startDatapath: could not duplicate framework TUN fd $originalFd: " +
                    describeFailure(failure),
                failure,
            )
        }

        try {
            check(nativeDescriptor.fileDescriptor.valid()) {
                "duplicated TUN descriptor is invalid (fd=${nativeDescriptor.fd})"
            }
            val nativeFd = nativeDescriptor.fd
            // This descriptor is a TestNetworkManager TUN, not an ordinary
            // socket. The Go endpoint is configured explicitly for a raw TUN.
            val started = try {
                Datapath.start(nativeFd.toLong(), mtu.toLong())
            } catch (failure: Throwable) {
                throw IllegalStateException(
                    "gVisor TUN attach failed: ${describeFailure(failure)}",
                    failure,
                )
            }
            datapathFileDescriptor = nativeDescriptor
            datapathSession = started
        } catch (failure: Throwable) {
            // Capture descriptor state before close(): ParcelFileDescriptor.fd
            // becomes -1 after close and would hide the actual fd in diagnostics.
            val duplicateFd = nativeDescriptor.fd
            val originalValid = runCatching { descriptor.fileDescriptor.valid() }.getOrDefault(false)
            val closeFailure = runCatching { nativeDescriptor.close() }.exceptionOrNull()
            if (closeFailure != null) failure.addSuppressed(closeFailure)
            throw IllegalStateException(
                "startDatapath failed: original fd=$originalFd, duplicate fd=$duplicateFd, " +
                    "mtu=$mtu, originalValid=$originalValid: " +
                    describeFailure(failure),
                failure,
            )
        }
    }

    private fun describeFailure(failure: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = failure
        repeat(5) {
            val item = current ?: return@repeat
            parts += "${item.javaClass.simpleName}: ${item.message ?: "(no message)"}"
            current = item.cause
        }
        return parts.joinToString(" <- ")
    }

    fun datapathStatsJson(): String? = runCatching { datapathSession?.statsJSON() }.getOrNull()

    fun bindDatapathTo(handle: Long) {
        val session = datapathSession
            ?: error("bindDatapathTo($handle): no datapath session; startDatapath must succeed first")

        session.setNetwork(handle)
    }

    private fun awaitAvailability(interfaceName: String, timeoutMs: Int): Network {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            findNetworkOn(interfaceName)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("test network '$interfaceName' did not become available within ${timeoutMs}ms")
    }

    private fun findNetworkOn(interfaceName: String): Network? =
        connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getLinkProperties(candidate)?.interfaceName == interfaceName
        }

    fun release(): List<String> {
        val problems = mutableListOf<String>()

        keepAliveCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                keepAliveCallback = null
            } catch (failure: Throwable) {
                problems += "unregisterNetworkCallback: ${failure.message}"
            }
        }

        // Never close either TUN descriptor or tear down its network while gVisor
        // may still be reading from the duplicated fd. Keep ownership references
        // intact so a later cleanup attempt can retry safely.
        datapathSession?.let { session ->
            try {
                session.stop()
                datapathSession = null
            } catch (failure: Throwable) {
                problems += "datapath.stop: ${failure.message}"
                problems.forEach { SessionLog.warn("teardown problem: $it") }
                return problems
            }
        }

        datapathFileDescriptor?.let { descriptor ->
            try {
                descriptor.close()
                datapathFileDescriptor = null
            } catch (failure: Throwable) {
                problems += "close(datapath fd): ${failure.message}"
                problems.forEach { SessionLog.warn("teardown problem: $it") }
                return problems
            }
        }

        network?.let { acquired ->
            try {
                testNetworkApi.teardownTestNetwork(acquired)
                network = null
            } catch (failure: Throwable) {
                problems += "teardownTestNetwork: ${failure.message}"
                problems.forEach { SessionLog.warn("teardown problem: $it") }
                return problems
            }
        }

        fileDescriptor?.let { descriptor ->
            try {
                descriptor.close()
                fileDescriptor = null
                tun = null
            } catch (failure: Throwable) {
                problems += "close(tun fd): ${failure.message}"
            }
        }

        problems.forEach { SessionLog.warn("teardown problem: $it") }
        return problems
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
    }
}
