package dev.shizzi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.lang.reflect.Method
import java.net.InetAddress

data class HiddenApiPath(
    val id: String,
    val className: String,
    val memberName: String,
    val since: Int,
    val notes: String,
)

object HiddenApiCatalog {
    val paths: List<HiddenApiPath> = listOf(
        HiddenApiPath(
            id = "TestNetworkManager.class",
            className = "android.net.TestNetworkManager",
            memberName = "<class>",
            since = 29,
            notes = "Obtained via Context.getSystemService(\"test_network\"); " +
                "@TestApi, not in the SDK. Absent on trimmed OEM builds.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.createTunInterface",
            className = "android.net.TestNetworkManager",
            memberName = "createTunInterface",
            since = 29,
            notes = "Two overloads exist, taking LinkAddress[] and " +
                "Collection<LinkAddress>. Both are probed, and both are passed " +
                "the IPv4 and IPv6 TUN addresses together.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.setupTestNetwork",
            className = "android.net.TestNetworkManager",
            memberName = "setupTestNetwork",
            since = 29,
            notes = "The (LinkProperties, boolean, IBinder) overload is used so " +
                "the network carries IPv6 DNS servers, without which tethering " +
                "will not provision IPv6 downstream; falls back to " +
                "(String iface, IBinder binder). Requires MANAGE_TEST_NETWORKS, " +
                "held by shell UID 2000.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.teardownTestNetwork",
            className = "android.net.TestNetworkManager",
            memberName = "teardownTestNetwork",
            since = 29,
            notes = "Takes the Network returned by the availability callback.",
        ),
        HiddenApiPath(
            id = "TestNetworkInterface.getFileDescriptor",
            className = "android.net.TestNetworkInterface",
            memberName = "getFileDescriptor",
            since = 29,
            notes = "Returns ParcelFileDescriptor owning the TUN. This fd is " +
                "the datapath handoff point.",
        ),
        HiddenApiPath(
            id = "TestNetworkInterface.getInterfaceName",
            className = "android.net.TestNetworkInterface",
            memberName = "getInterfaceName",
            since = 29,
            notes = "Yields the testtunN name matched against dumpsys output.",
        ),
        HiddenApiPath(
            id = "TetheringManager.setPreferTestNetworks",
            className = "android.net.TetheringManager",
            memberName = "setPreferTestNetworks",
            since = 32,
            notes = "THE load-bearing call: without it the tethering stack never " +
                "selects the TUN. @TestApi, gated on TETHER_PRIVILEGED. Carried " +
                "by the tethering module rather than the platform, so a build " +
                "below 33 has it only if that module is new enough.",
        ),
        HiddenApiPath(
            id = "WifiManager.startTetheredHotspot",
            className = "android.net.wifi.WifiManager",
            memberName = "startTetheredHotspot",
            since = 30,
            notes = "@SystemApi. Reaches SoftAp through WifiServiceImpl rather " +
                "than TetheringService, whose hasTetherChangePermission rejects " +
                "shell. Passing a null SoftApConfiguration uses the saved hotspot.",
        ),
        HiddenApiPath(
            id = "TetheringManager.startTethering",
            className = "android.net.TetheringManager",
            memberName = "startTethering",
            since = 30,
            notes = "Takes (TetheringRequest, Executor, StartTetheringCallback), " +
                "all hidden. Needs TETHER_PRIVILEGED, which shell holds. No " +
                "`cmd tethering` equivalent exists, so reflection is the only path.",
        ),
        HiddenApiPath(
            id = "TetheringManager.stopTethering",
            className = "android.net.TetheringManager",
            memberName = "stopTethering",
            since = 30,
            notes = "Takes the int tethering type (0 = TETHERING_WIFI). Required " +
                "to restart the downstream so upstream selection re-runs (R4.4).",
        ),
        HiddenApiPath(
            id = "LinkAddress.<init>",
            className = "android.net.LinkAddress",
            memberName = "<init>",
            since = 29,
            notes = "The (InetAddress, int) constructor is package-private, so " +
                "the TUN address must be built reflectively.",
        ),
        HiddenApiPath(
            id = "NetworkCapabilities.TRANSPORT_TEST",
            className = "android.net.NetworkCapabilities",
            memberName = "TRANSPORT_TEST",
            since = 29,
            notes = "@hide constant (value 7 on AOSP). Read reflectively rather " +
                "than hardcoded so a renumbering surfaces as a probe failure.",
        ),
    )
}

val TEST_NETWORK_DNS_SERVERS: List<InetAddress>
    get() = listOf("2001:4860:4860::8888", "8.8.8.8").map(InetAddress::getByName)

fun buildLinkAddress(address: InetAddress, prefixLength: Int): LinkAddress {
    val constructor = LinkAddress::class.java
        .getDeclaredConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
    constructor.isAccessible = true
    return constructor.newInstance(address, prefixLength)
}

fun resolveTransportTest(): Int = runCatching {
    NetworkCapabilities::class.java.getField("TRANSPORT_TEST").getInt(null)
}.getOrDefault(TRANSPORT_TEST_AOSP_FALLBACK)

const val TRANSPORT_TEST_AOSP_FALLBACK = 7

sealed interface Resolution {
    data class Found(val path: HiddenApiPath) : Resolution
    data class Missing(val path: HiddenApiPath, val reason: String) : Resolution
}

class TestNetworkApi(private val context: Context) {

    private val managerClass: Class<*>? = runCatching {
        Class.forName("android.net.TestNetworkManager")
    }.getOrNull()

    @SuppressLint("WrongConstant")
    private val manager: Any? = runCatching {

        context.getSystemService("test_network")
    }.getOrNull()

    val isAvailable: Boolean get() = managerClass != null && manager != null

    fun resolveAll(): List<Resolution> = HiddenApiCatalog.paths.map { path ->
        resolveOne(path)
    }

    private fun resolveOne(path: HiddenApiPath): Resolution {
        val owner = runCatching { Class.forName(path.className) }.getOrNull()
            ?: return Resolution.Missing(path, "class not found: ${path.className}")

        return when (path.memberName) {
            "<class>" -> Resolution.Found(path)
            "<init>" -> resolveConstructor(owner, path)
            else -> resolveMethodOrField(owner, path)
        }
    }

    private fun resolveConstructor(owner: Class<*>, path: HiddenApiPath): Resolution = when {
        owner.declaredConstructors.isNotEmpty() -> Resolution.Found(path)
        else -> Resolution.Missing(path, "no declared constructor on ${path.className}")
    }

    private fun resolveMethodOrField(owner: Class<*>, path: HiddenApiPath): Resolution {
        val hasMethod = owner.declaredMethods.any { it.name == path.memberName }
        val hasField = owner.declaredFields.any { it.name == path.memberName }
        return when {
            hasMethod || hasField -> Resolution.Found(path)
            else -> Resolution.Missing(path, "no method or field '${path.memberName}' on ${path.className}")
        }
    }

    fun createTunInterface(addresses: List<LinkAddress>): TunHandle {
        val instance = manager ?: error("createTunInterface: test_network service unavailable")
        val owner = managerClass ?: error("createTunInterface: TestNetworkManager class absent")

        // Preserve platform exceptions: swallowing InvocationTargetException hid
        // OEM/API incompatibilities as if they were invalid TUN descriptors.
        val failures = mutableListOf<Throwable>()
        var created: Any? = null
        for ((label, factory) in listOf(
            "Collection" to { invokeCollectionOverload(owner, instance, addresses) },
            "LinkAddress[]" to { invokeArrayOverload(owner, instance, addresses) },
        )) {
            try {
                created = factory()
                if (created != null) break
                failures += IllegalStateException("$label overload returned null")
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (created == null) {
            val detail = failures.joinToString("; ") {
                "${it.javaClass.simpleName}: ${it.message}"
            }
            throw IllegalStateException(
                "createTunInterface: all overloads failed for ${addresses.joinToString()}: $detail",
                failures.firstOrNull(),
            ).also { error -> failures.drop(1).forEach(error::addSuppressed) }
        }

        return TunHandle(created)
    }

    private fun invokeCollectionOverload(
        owner: Class<*>,
        instance: Any,
        addresses: List<LinkAddress>,
    ): Any? = invokeTunFactory(owner, instance, Collection::class.java, addresses)

    private fun invokeArrayOverload(
        owner: Class<*>,
        instance: Any,
        addresses: List<LinkAddress>,
    ): Any? {
        val arrayType = Class.forName("[Landroid.net.LinkAddress;")
        val argument = java.lang.reflect.Array.newInstance(LinkAddress::class.java, addresses.size)
        addresses.forEachIndexed { index, address ->
            java.lang.reflect.Array.set(argument, index, address)
        }
        return invokeTunFactory(owner, instance, arrayType, argument)
    }

    private fun invokeTunFactory(
        owner: Class<*>,
        instance: Any,
        parameterType: Class<*>,
        argument: Any,
    ): Any? {
        val method = owner.getMethod("createTunInterface", parameterType)
        return try {
            method.invoke(instance, argument)
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            throw wrapped.targetException ?: wrapped
        }
    }

    fun setupTestNetwork(interfaceName: String, dnsServers: List<InetAddress>, binder: IBinder) {
        val instance = manager ?: error("setupTestNetwork: test_network service unavailable")
        val owner = managerClass ?: error("setupTestNetwork: TestNetworkManager class absent")

        val properties = LinkProperties().apply {
            setInterfaceName(interfaceName)
            val addDnsServer = LinkProperties::class.java
                .getMethod("addDnsServer", InetAddress::class.java)
            dnsServers.forEach { addDnsServer.invoke(this, it) }
        }
        try {
            if (setupWithLinkProperties(owner, instance, properties to binder)) return
            val method = owner.getMethod("setupTestNetwork", String::class.java, IBinder::class.java)
            method.invoke(instance, interfaceName, binder)
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "setupTestNetwork failed for $interfaceName: " +
                    "${wrapped.targetException?.javaClass?.simpleName}: " +
                    "${wrapped.targetException?.message}",
                wrapped.targetException ?: wrapped,
            )
        }
    }

    private fun setupWithLinkProperties(
        owner: Class<*>,
        instance: Any,
        request: Pair<LinkProperties, IBinder>,
    ): Boolean {
        val method = runCatching {
            owner.getMethod(
                "setupTestNetwork",
                LinkProperties::class.java,
                Boolean::class.javaPrimitiveType,
                IBinder::class.java,
            )
        }.getOrNull() ?: return false
        return try {
            method.invoke(instance, request.first, true, request.second)
            true
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "setupTestNetwork(LinkProperties) failed: " +
                    "${wrapped.targetException?.javaClass?.simpleName}: " +
                    "${wrapped.targetException?.message}",
                wrapped.targetException ?: wrapped,
            )
        }
    }

    fun teardownTestNetwork(network: Network) {
        val instance = manager ?: return
        val owner = managerClass ?: return
        val method = owner.getMethod("teardownTestNetwork", Network::class.java)
        try {
            method.invoke(instance, network)
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException(
                "teardownTestNetwork failed: ${wrapped.targetException?.message}",
                wrapped.targetException ?: wrapped,
            )
        }
    }
}

class TunHandle(private val delegate: Any) {

    val interfaceName: String
        get() {
            val method = delegate.javaClass.getMethod("getInterfaceName")
            return method.invoke(delegate) as String
        }

    val fileDescriptor: ParcelFileDescriptor
        get() {
            val method = delegate.javaClass.getMethod("getFileDescriptor")
            return method.invoke(delegate) as ParcelFileDescriptor
        }
}

class TetheringPreferenceApi(private val context: Context) {

    private fun resolveMethod(): Method {
        val owner = Class.forName("android.net.TetheringManager")
        return owner.getMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType)
    }

    private fun tetheringManager(): Any =
        context.getSystemService("tethering")
            ?: error("setPreferTestNetworks: tethering service unavailable")

    fun setPreferTestNetworks(prefer: Boolean) {
        val method = resolveMethod()
        try {
            method.invoke(tetheringManager(), prefer)
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            val cause = wrapped.targetException ?: wrapped
            throw IllegalStateException(
                "setPreferTestNetworks($prefer) failed: ${cause.javaClass.simpleName}: ${cause.message}",
                cause,
            )
        }
    }

    fun resolutionFailure(): String? = runCatching { resolveMethod() }.fold(
        onSuccess = { null },
        onFailure = { failure -> "${failure.javaClass.simpleName}: ${failure.message}" },
    )
}

fun Context.connectivityManager(): ConnectivityManager =
    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
