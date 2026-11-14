package com.seloomwarp.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.*
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Sing-box adapter sharing the existing AlkhalidiVpnService and its single TUN.
 * It intentionally does not declare or start a second Android VpnService.
 */
internal class SingBoxEngine(
    private val vpn: AlkhalidiVpnService,
    private val config: String,
    private val displayName: String,
) : VpnEngineManager.Engine, PlatformInterface, CommandServerHandler {

    companion object { private const val TAG = "NexaLinkSingBox" }

    @Volatile private var running = false
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var statsClient: CommandClient? = null
    private val statsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var statsClientStopped = false
    private val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
    private val interfaceMonitors = java.util.concurrent.ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    override fun id(): String = "sing-box"
    override fun isRunning(): Boolean = running

    override fun start() {
        check(!running) { "Sing-box is already running" }
        val base = File(vpn.filesDir, "libbox").apply { mkdirs() }
        val work = File(vpn.getExternalFilesDir(null) ?: vpn.filesDir, "libbox-work").apply { mkdirs() }
        val temp = File(vpn.cacheDir, "libbox-temp").apply { mkdirs() }
        val setup = SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = work.absolutePath
            tempPath = temp.absolutePath
            fixAndroidStack = true
            logMaxLines = 1000
            debug = false
            crashReportSource = "NexaLink"
            appVersion = "1"
            appMarketingVersion = "2.2.0"
            oomKillerEnabled = false
            oomKillerDisabled = false
            oomMemoryLimit = 0L
            powerReportEnabled = false
            platformMetadata = "{\"os\":\"Android\",\"sdk\":" + Build.VERSION.SDK_INT + "}"
        }

        try {
            Libbox.setup(setup)
            val server = CommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            check(tunDescriptor != null) { "Sing-box did not establish the Android TUN interface" }
            running = true
            startStatsClient()
            Log.i(TAG, "Sing-box started: " + displayName)
        } catch (failure: Throwable) {
            running = false
            stopStatsClient()
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { tunDescriptor?.close() }
            tunDescriptor?.let { vpn.onSingBoxTunClosed(it) }
            tunDescriptor = null
            throw IllegalStateException("Unable to start Sing-box: " + failure.message, failure)
        }
    }

    override fun stop() {
        running = false
        stopStatsClient()
        var failure: Throwable? = null
        runCatching { commandServer?.closeService() }.onFailure { failure = it }
        runCatching { commandServer?.close() }.onFailure {
            if (failure == null) failure = it else failure?.addSuppressed(it)
        }
        commandServer = null
        runCatching { tunDescriptor?.close() }.onFailure {
            if (failure == null) failure = it else failure?.addSuppressed(it)
        }
        tunDescriptor?.let { vpn.onSingBoxTunClosed(it) }
        tunDescriptor = null
        failure?.let { throw IllegalStateException("Unable to stop Sing-box cleanly", it) }
    }

    private val statsHandler = object : CommandClientHandler {
        override fun connected() {
            Log.i(TAG, "Sing-box status client connected")
        }
        override fun disconnected(message: String?) {
            if (!statsClientStopped && !message.isNullOrBlank()) {
                Log.w(TAG, "Sing-box status client disconnected: " + message)
            }
        }
        override fun setDefaultLogLevel(level: Int) { }
        override fun clearLogs() { }
        override fun writeLogs(messageList: LogIterator?) { }
        override fun writeStatus(message: StatusMessage) {
            if (!statsClientStopped) {
                vpn.onSingBoxTrafficStats(
                    message.uplinkTotal,
                    message.downlinkTotal,
                    message.trafficAvailable,
                )
            }
        }
        override fun writeGroups(message: OutboundGroupIterator?) { }
        override fun writeOutbounds(message: OutboundGroupItemIterator?) { }
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) { }
        override fun updateClashMode(newMode: String) { }
        override fun writeConnectionEvents(events: ConnectionEvents?) { }
    }

    private fun startStatsClient() {
        statsClientStopped = false
        statsExecutor.execute {
            if (statsClientStopped || !running) return@execute
            try {
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = 1_000_000_000L
                }
                val client = CommandClient(statsHandler, options)
                statsClient = client
                client.connect()
            } catch (error: Exception) {
                if (!statsClientStopped) Log.w(TAG, "Unable to subscribe to Sing-box traffic statistics", error)
            }
        }
    }

    private fun stopStatsClient() {
        statsClientStopped = true
        val client = statsClient
        statsClient = null
        runCatching { client?.disconnect() }
        runCatching { statsExecutor.shutdownNow() }
    }

    // CommandServerHandler
    override fun serviceStop() { running = false; vpn.onSingBoxEngineStopped() }
    override fun serviceReload() { }
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }
    override fun setSystemProxyEnabled(enabled: Boolean) { }
    override fun triggerNativeCrash() { throw IllegalStateException("Native crash requested") }
    override fun writeDebugMessage(message: String?) { Log.d(TAG, message ?: "") }
    override fun connectSSHAgent(): Int = -1

    // PlatformInterface
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpn.protect(fd)) { "VpnService.protect failed for fd " + fd }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpn) == null) { "Android VPN permission is missing" }
        val builder = vpn.Builder().setSession(displayName).setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val ipv4 = options.inet4Address
        while (ipv4.hasNext()) {
            val address = ipv4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val ipv6 = options.inet6Address
        while (ipv6.hasNext()) {
            val address = ipv6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4 = options.inet4RouteAddress
                if (v4.hasNext()) while (v4.hasNext()) {
                    val route = v4.next()
                    builder.addRoute(route.address(), route.prefix())
                } else if (options.inet4Address.hasNext()) builder.addRoute("0.0.0.0", 0)

                val v6 = options.inet6RouteAddress
                if (v6.hasNext()) while (v6.hasNext()) {
                    val route = v6.next()
                    builder.addRoute(route.address(), route.prefix())
                } else if (options.inet6Address.hasNext()) builder.addRoute("::", 0)

                val ex4 = options.inet4RouteExcludeAddress
                while (ex4.hasNext()) {
                    val route = ex4.next()
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(route.address()), route.prefix()))
                }
                val ex6 = options.inet6RouteExcludeAddress
                while (ex6.hasNext()) {
                    val route = ex6.next()
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(route.address()), route.prefix()))
                }
            } else {
                val v4 = options.inet4RouteRange
                while (v4.hasNext()) {
                    val route = v4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val v6 = options.inet6RouteRange
                while (v6.hasNext()) {
                    val route = v6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
            val included = options.includePackage
            while (included.hasNext()) {
                val packageName = included.next()
                runCatching { builder.addAllowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "Unable to include app " + packageName, it) }
            }
            val excluded = options.excludePackage
            while (excluded.hasNext()) {
                val packageName = excluded.next()
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "Unable to exclude app " + packageName, it) }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bypass = mutableListOf<String>()
            val iterator = options.httpProxyBypassDomain
            while (iterator.hasNext()) bypass += iterator.next()
            builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(
                options.httpProxyServer, options.httpProxyServerPort, bypass
            ))
        }

        val descriptor = builder.establish()
            ?: error("Android refused to establish the Sing-box TUN interface")
        tunDescriptor?.close()
        tunDescriptor = descriptor
        vpn.onSingBoxTunEstablished(descriptor)
        return descriptor.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Connection owner lookup requires Android 10+")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol, InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        check(uid >= 0) { "Connection owner not found" }
        val packages = vpn.packageManager.getPackagesForUid(uid).orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (interfaceMonitors.containsKey(listener)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reportDefaultInterface(listener, connectivity.activeNetwork ?: network)
            }
            override fun onLost(network: Network) {
                reportDefaultInterface(listener, connectivity.activeNetwork)
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                reportDefaultInterface(listener, connectivity.activeNetwork ?: network)
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            interfaceMonitors[listener] = callback
            reportDefaultInterface(listener, connectivity.activeNetwork)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to monitor the default network", error)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = interfaceMonitors.remove(listener) ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun reportDefaultInterface(listener: InterfaceUpdateListener, network: Network?) {
        if (network == null) return
        val properties = connectivity.getLinkProperties(network) ?: return
        val name = properties.interfaceName ?: return
        val physical = runCatching { JavaNetworkInterface.getByName(name) }.getOrNull() ?: return
        val caps = connectivity.getNetworkCapabilities(network)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        listener.updateDefaultInterface(name, physical.index, expensive, false)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val values = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val systemInterfaces = runCatching {
            JavaNetworkInterface.getNetworkInterfaces().toList()
        }.getOrDefault(emptyList())
        for (network in connectivity.allNetworks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = properties.interfaceName ?: continue
            val physical = systemInterfaces.firstOrNull { it.name == name } ?: continue
            val item = io.nekohasekai.libbox.NetworkInterface().apply {
                this.name = name
                index = physical.index
                mtu = runCatching { physical.mtu }.getOrDefault(1500)
                addresses = StringArray(physical.interfaceAddresses.map {
                    it.address.hostAddress + "/" + it.networkPrefixLength
                }.iterator())
                dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress }.iterator())
                gateway = StringArray(properties.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.hostAddress }.iterator())
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                flags = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                } else 0
            }
            values += item
        }
        return InterfaceArray(values.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() { }
    override fun sendNotification(notification: Notification) { }
    override fun cancelNotification(identifier: String, typeID: Int) { }
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) { }
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) { }
    override fun registerMyInterface(name: String?) { }
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() { error("Platform shell is not supported") }
    override fun openShellSession(
        user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int,
    ): ShellSession = error("Platform shell is not supported")
    override fun lookupUser(username: String?): PlatformUser = error("Platform user lookup is not supported")
    override fun lookupSFTPServer(): String = error("SFTP is not supported")
    override fun readSystemSSHHostKey(): String = error("SSH host key is not available")
    override fun tailscaleHostname(): String = Build.MANUFACTURER + " " + Build.MODEL
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("Platform bridge is not supported")

    private class StringArray(iterator: Iterator<String>) : StringIterator {
        private val values = iterator.asSequence().toList()
        private var index = 0
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }
    private class InterfaceArray(
        private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }
}
