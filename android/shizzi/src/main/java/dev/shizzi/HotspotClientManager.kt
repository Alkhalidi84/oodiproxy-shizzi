package dev.shizzi

import android.content.Context
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the tethered Soft AP client list and applies the platform SoftApConfiguration
 * block list. Per-station byte counters are deliberately reported as unavailable:
 * Android's public tethering counters are interface-wide, not MAC-address scoped.
 */
class HotspotClientManager(private val context: Context) {

    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSnapshot: JSONObject? = null

    @Synchronized
    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        cachedSnapshot?.takeIf { now - cachedAt < SNAPSHOT_CACHE_MS }?.let {
            return JSONObject(it.toString())
        }
        val fresh = readSnapshot()
        cachedAt = now
        cachedSnapshot = fresh
        return JSONObject(fresh.toString())
    }

    private fun readSnapshot(): JSONObject {
        val observation = UpstreamInspector().observeWifi()
        val clients = if (observation.didTimeout) emptySet() else parseConnectedMacs(observation.rawOutput)
        val addresses = readArpTable()
        val config = readSoftApConfiguration()
        val blocked = config?.let(::readBlockedMacs).orEmpty()
        val blockSupported = config != null && canBuildBlockList(config, observation.rawOutput)

        val all = (clients + blocked).sorted()
        val rows = JSONArray()
        all.forEach { mac ->
            rows.put(JSONObject().apply {
                put("macAddress", mac)
                put("ipAddress", addresses[mac] ?: JSONObject.NULL)
                put("connected", mac in clients)
                put("blocked", mac in blocked)
                put("rxBytes", JSONObject.NULL)
                put("txBytes", JSONObject.NULL)
                put("usageAvailable", false)
            })
        }

        return JSONObject().apply {
            put("clients", rows)
            put("connectedCount", clients.size)
            put("blockedCount", blocked.size)
            put("inventoryAvailable", !observation.didTimeout && clients.isNotEmpty() || !observation.didTimeout)
            put("blockSupported", blockSupported)
            put("usageAvailable", false)
            put(
                "message",
                when {
                    observation.didTimeout -> "Wi-Fi client inventory timed out"
                    config == null -> "Client blocking is unavailable on this device or permission profile"
                    else -> "Per-device traffic counters are not exposed by this Android tethering implementation"
                },
            )
        }
    }

    fun setBlocked(macAddress: String, shouldBlock: Boolean): JSONObject {
        val mac = normalizeMac(macAddress)
            ?: return result(false, "Invalid MAC address")
        val wifi = context.getSystemService(Context.WIFI_SERVICE)
            ?: return result(false, "Wi-Fi service is unavailable")
        val config = readSoftApConfiguration()
            ?: return result(false, "Soft AP configuration API is unavailable")
        return runCatching {
            val wifiDump = UpstreamInspector().observeWifi()
            check(!wifiDump.didTimeout && hasBlockListSupport(wifiDump.rawOutput)) {
                "This Wi-Fi chipset/firmware does not advertise client force-disconnect support"
            }
            val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val macClass = Class.forName("android.net.MacAddress")
            val blocked = readBlockedMacs(config).toMutableSet()
            if (shouldBlock) blocked.add(mac) else blocked.remove(mac)

            val macFactory = macClass.getMethod("fromString", String::class.java)
            val macObjects = ArrayList<Any>(blocked.size)
            blocked.sorted().forEach { value -> macObjects.add(macFactory.invoke(null, value)) }

            val builder = builderClass.getConstructor(configClass).newInstance(config)
            builderClass.getMethod("setBlockedClientList", List::class.java)
                .invoke(builder, macObjects)
            val updated = builderClass.getMethod("build").invoke(builder)
            val applied = wifi.javaClass.getMethod("setSoftApConfiguration", configClass)
                .invoke(wifi, updated) as? Boolean ?: false
            check(applied) { "Android rejected the Soft AP configuration update" }

            val verified = readSoftApConfiguration()
                ?: error("Could not verify the updated Soft AP configuration")
            check((mac in readBlockedMacs(verified)) == shouldBlock) {
                "The device did not retain the requested block-list change"
            }
            cachedSnapshot = null
            result(true, if (shouldBlock) "Device added to the hotspot block list" else "Device removed from the hotspot block list")
        }.getOrElse { failure ->
            val reason = failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName
            SessionLog.warn("hotspot client block-list update failed: $reason")
            result(false, "Could not update the hotspot block list: $reason")
        }
    }

    private fun result(success: Boolean, message: String) = JSONObject().apply {
        put("success", success)
        put("message", message)
    }

    private fun readSoftApConfiguration(): Any? = runCatching {
        val wifi = context.getSystemService(Context.WIFI_SERVICE)
            ?: error("Wi-Fi service unavailable")
        wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)
    }.getOrNull()

    private fun canBuildBlockList(configuration: Any, wifiDump: String): Boolean =
        hasBlockListSupport(wifiDump) && runCatching {
            val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            builderClass.getConstructor(configClass)
            builderClass.getMethod("setBlockedClientList", List::class.java)
        }.isSuccess

    private fun hasBlockListSupport(wifiDump: String): Boolean {
        val featureMask = Regex("""(?i)(?:mSupportedFeatures|supportedFeatures)\s*[=:]\s*(0x[0-9a-f]+|[0-9]+)""")
            .findAll(wifiDump)
            .mapNotNull { match ->
                val value = match.groupValues[1]
                runCatching {
                    if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16)
                    else value.toLong()
                }.getOrNull()
            }
            .toList()
        return featureMask.any { mask -> (mask and SOFT_AP_FEATURE_CLIENT_FORCE_DISCONNECT) != 0L }
    }

    private fun readBlockedMacs(configuration: Any): Set<String> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val values = configuration.javaClass.getMethod("getBlockedClientList")
            .invoke(configuration) as? List<Any?> ?: emptyList()
        values.mapNotNull { it?.toString()?.let(::normalizeMac) }.toSet()
    }.getOrDefault(emptySet())

    private fun parseConnectedMacs(output: String): Set<String> {
        val lines = output.lineSequence().toList()
        val result = linkedSetOf<String>()
        var remaining = 0
        lines.forEach { line ->
            val lower = line.lowercase(Locale.ROOT)
            val isSection = CLIENT_SECTION_MARKERS.any(lower::contains)
            if (isSection) remaining = 14
            if (remaining > 0 || lower.contains("wificlient")) {
                MAC_PATTERN.findAll(line).forEach { match ->
                    normalizeMac(match.value)?.let(result::add)
                }
                if (remaining > 0) remaining--
                if (remaining > 0 && line.contains(']')) remaining = 0
            }
        }
        return result
    }

    private fun readArpTable(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < 6 || fields[2] == "0x0") return@mapNotNull null
            val mac = normalizeMac(fields[3]) ?: return@mapNotNull null
            mac to fields[0]
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun normalizeMac(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT).replace('-', ':')
        return normalized.takeIf { MAC_FULL.matches(it) }
    }

    private companion object {
        val MAC_PATTERN = Regex("""(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}""")
        val MAC_FULL = Regex("""(?:[0-9a-f]{2}:){5}[0-9a-f]{2}""")
        val WHITESPACE = Regex("""\s+""")
        const val SNAPSHOT_CACHE_MS = 4_000L
        const val SOFT_AP_FEATURE_CLIENT_FORCE_DISCONNECT = 1L shl 1
        val CLIENT_SECTION_MARKERS = listOf(
            "mconnectedclients",
            "mconnectedclientlist",
            "connectedclients:",
            "softap clients:",
        )
    }
}
