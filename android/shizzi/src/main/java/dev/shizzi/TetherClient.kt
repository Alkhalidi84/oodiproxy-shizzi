package dev.shizzi

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class UiStatus { READY, LOADING, CONNECTED, ERROR }

data class HotspotClient(val macAddress: String, val ipAddress: String?, val connected: Boolean, val blocked: Boolean, val usageAvailable: Boolean = false, val rxBytes: Long = 0L, val txBytes: Long = 0L)
data class HotspotClientsState(val clients: List<HotspotClient> = emptyList(), val blockSupported: Boolean = false, val usageAvailable: Boolean = false, val message: String = "Start the hotspot to inspect connected devices", val actionMessage: String? = null)

data class SessionUiState(
    val shizukuState: ShizukuState = ShizukuState.NotRunning,
    val status: UiStatus = UiStatus.READY,
    val isBusy: Boolean = false,
    val detail: String = "",
    val interfaceName: String = "",
    val lastError: String = "",
    val isVpnBound: Boolean = false,
    val isVpnBypassed: Boolean = false,

    val clientCount: Int = 0,

    val traffic: Traffic = Traffic(),
    val hotspotClients: HotspotClientsState = HotspotClientsState(),
) {

    val canStart: Boolean get() = shizukuState is ShizukuState.Ready && !isBusy
}

fun SessionUiState.asStopped(): SessionUiState = copy(
    isBusy = false,
    status = UiStatus.READY,
    lastError = "",
    detail = "Stopped",
    interfaceName = "",
    isVpnBound = false,
    isVpnBypassed = false,
    clientCount = 0,
    traffic = Traffic(),
    hotspotClients = HotspotClientsState(message = "Hotspot is stopped"),
)

fun SessionUiState.applyOutcome(outcome: Result<String>): SessionUiState {
    val failure = outcome.exceptionOrNull()
    if (failure != null) {
        return copy(
            isBusy = false,
            status = UiStatus.ERROR,
            lastError = "${failure.javaClass.simpleName}: ${failure.message}",

            interfaceName = "",
            isVpnBound = false,
            isVpnBypassed = false,
            clientCount = 0,
            traffic = Traffic(),
            hotspotClients = HotspotClientsState(message = "Hotspot session ended"),
        )
    }

    val parsed = runCatching { JSONObject(outcome.getOrDefault("{}")) }.getOrNull()
    val sessionState = parsed?.optString("state").orEmpty()
    val sessionDetail = parsed?.optString("detail").orEmpty()

    return copy(
        isBusy = false,
        status = statusFor(sessionState),
        detail = sessionDetail,
        interfaceName = parsed?.optString("interface").orEmpty().takeIf { it != "null" }.orEmpty(),
        lastError = if (sessionState == "ERROR") sessionDetail else "",

        isVpnBound = parsed?.optBoolean("isVpnBound") == true,
        isVpnBypassed = parsed?.optBoolean("isVpnBypassed") == true,
        clientCount = parsed?.optInt("clientCount") ?: 0,
        traffic = Traffic(
            up = parsed?.optLong("bytesUp") ?: 0,
            down = parsed?.optLong("bytesDown") ?: 0,
        ),
        hotspotClients = parseHotspotClients(parsed?.optJSONObject("clientInventory")),
    )
}

private fun parseHotspotClients(json: JSONObject?): HotspotClientsState {
    if (json == null) return HotspotClientsState()
    val rows = json.optJSONArray("clients")
    val clients = buildList {
        if (rows != null) for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            add(HotspotClient(
                macAddress = row.optString("macAddress"),
                ipAddress = row.optString("ipAddress").takeUnless { it.isBlank() || it == "null" },
                connected = row.optBoolean("connected"),
                blocked = row.optBoolean("blocked"),
                usageAvailable = row.optBoolean("usageAvailable"),
                rxBytes = row.optLong("rxBytes", 0L),
                txBytes = row.optLong("txBytes", 0L),
            ))
        }
    }
    return HotspotClientsState(
        clients = clients,
        blockSupported = json.optBoolean("blockSupported"),
        usageAvailable = json.optBoolean("usageAvailable"),
        message = json.optString("message"),
    )
}

private fun statusFor(sessionState: String): UiStatus = when (sessionState) {
    "ACTIVE" -> UiStatus.CONNECTED
    "ERROR" -> UiStatus.ERROR
    "STARTING" -> UiStatus.LOADING
    else -> UiStatus.READY
}

class TetherClient {

    private var boundService: ITetherService? = null
    private var pendingBind: CompletableDeferred<ITetherService>? = null

    var onSessionLost: (() -> Unit)? = null

    private var deathRecipient: IBinder.DeathRecipient? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, TetherService::class.java.name),
    )

        .daemon(true)

        .tag(SERVICE_TAG)
        .processNameSuffix("probe")

        .debuggable(BuildConfig.DEBUG)
        .version(TetherService.CONTRACT_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { ITetherService.Stub.asInterface(it) }
            when (service) {
                null -> pendingBind?.completeExceptionally(
                    IllegalStateException("bindUserService: Shizuku returned a null binder"),
                )

                else -> {
                    boundService = service
                    pendingBind?.complete(service)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val hadService = boundService != null
            boundService = null
            pendingBind?.completeExceptionally(
                IllegalStateException("Shizuku UserService disconnected before binding completed"),
            )
            if (hadService) onSessionLost?.invoke()
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            boundService = null
            pendingBind?.completeExceptionally(
                IllegalStateException("Shizuku returned a null UserService binding"),
            )
        }
    }

    private suspend fun service(): ITetherService = bindMutex.withLock {
        boundService?.let { existing ->
            if (existing.asBinder().pingBinder()) return@withLock existing
            boundService = null
        }

        check(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            "Shizuku is not running. Start Shizuku and retry."
        }
        check(runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)) {
            "Shizuku permission has not been granted to NexaLink."
        }

        val serverUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        check(serverUid == 2000 || serverUid == 0) {
            "Unsupported Shizuku server identity: uid=$serverUid (expected shell 2000 or root 0)."
        }

        val deferred = CompletableDeferred<ITetherService>()
        pendingBind = deferred
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (failure: Throwable) {
            pendingBind = null
            throw IllegalStateException(
                "Shizuku could not start the privileged tethering service: ${failure.message}",
                failure,
            )
        }

        val bound = awaitBind(deferred)
        observeDeath(bound)
        bound
    }

    private suspend fun awaitBind(deferred: CompletableDeferred<ITetherService>): ITetherService =
        try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            pendingBind = null
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
            throw IllegalStateException(
                "bindUserService: Shizuku returned no binder for the privileged " +
                    "helper within ${BIND_TIMEOUT_MS}ms. The shell process either " +
                    "never started or exited before handing one back; " +
                    "`adb logcat -s ShizukuServiceStarter:*` carries the reason.",
                timeout,
            )
        }

    private fun observeDeath(bound: ITetherService) {
        deathRecipient = null

        val binder = bound.asBinder()
        val recipient = IBinder.DeathRecipient {
            boundService = null
            onSessionLost?.invoke()
        }

        runCatching { binder.linkToDeath(recipient, 0) }
            .onSuccess {
                deathRecipient = recipient
                if (!binder.pingBinder()) {
                    boundService = null
                    throw IllegalStateException("Shizuku UserService died immediately after binding")
                }
            }
            .onFailure { failure ->
                boundService = null
                throw IllegalStateException(
                    "Could not monitor the Shizuku UserService binder lifecycle: ${failure.message}",
                    failure,
                )
            }
    }

    suspend fun runProbes(attemptTethering: Boolean): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.runProbes(attemptTethering, AVAILABILITY_TIMEOUT_MS)
    }

    suspend fun checkCompatibility(): List<CapabilityResult> = withContext(Dispatchers.IO) {
        withTimeout(COMPATIBILITY_TIMEOUT_MS) {
            val bound = service()
            verifyContract(bound)
            parseCapabilities(bound.checkCompatibility())
        }
    }

    suspend fun installTetheringApex(path: String): StagingOutcome =
        withContext(Dispatchers.IO) {
            val bound = service()
            verifyContract(bound)

            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { apex -> parseStagingOutcome(bound.installTetheringApex(apex)) }
        }

    suspend fun rebootDevice(): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.rebootDevice()
    }

    suspend fun start(logging: Boolean, vpnMode: VpnMode): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.start(logging, vpnMode.name)
    }

    fun setLogging(enabled: Boolean) {
        runCatching { boundService?.setLogging(enabled) }
    }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        service().stop()
    }

    suspend fun clearLog(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bound = service()

            verifyContract(bound)
            bound.clearLog()
        }.fold(
            onSuccess = { null },
            onFailure = { failure -> failure.message ?: "could not reach Shizuku" },
        )
    }

    suspend fun releaseOrphanedDownstream(): String? = withContext(Dispatchers.IO) {
        runCatching { service().stop() }
            .fold(
                onSuccess = { null },
                onFailure = { failure ->
                    "could not reach Shizuku to drop the hotspot: ${failure.message}"
                },
            )
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        service().status
    }

    suspend fun setClientBlocked(macAddress: String, blocked: Boolean): String =
        withContext(Dispatchers.IO) {
            val bound = service()
            verifyContract(bound)
            bound.setClientBlocked(macAddress, blocked)
        }

    private fun verifyContract(bound: ITetherService) {
        val remote = bound.contractVersion
        check(remote == TetherService.CONTRACT_VERSION) {
            "UserService is stale: app is build ${TetherService.CONTRACT_VERSION}, " +
                "shell process reports $remote — press Stop then Start to reload it"
        }
    }

    fun unbind() = releaseBinding(shouldTerminate = false)

    fun unbindAndStopDaemon() = releaseBinding(shouldTerminate = true)

    private fun releaseBinding(shouldTerminate: Boolean) {
        pendingBind?.cancel()
        pendingBind = null

        deathRecipient?.let { recipient ->
            runCatching { boundService?.asBinder()?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null

        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, shouldTerminate) }
        boundService = null
    }

    private companion object {

        private const val SERVICE_TAG = "shizzi-session"

        const val BIND_TIMEOUT_MS = 35_000L

        const val AVAILABILITY_TIMEOUT_MS = 10_000

        private const val COMPATIBILITY_TIMEOUT_MS = 15_000L
    }
}
