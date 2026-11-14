package dev.shizzi

import android.content.Context
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the tethered Soft AP client list and applies the platform SoftApConfiguration
 * block list. Experimental per-client counters account only for payload relayed through
 * the userspace datapath and rely on an IP-to-MAC match from the ARP table.
 */
class HotspotClientManager(private val context: Context) {

    private val usagePreferences by lazy {
        context.getSharedPreferences("nexalink_hotspot_usage_v1", Context.MODE_PRIVATE)
    }

    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSnapshot: JSONObject? = null

    @Synchronized
    fun snapshot(datapathStatsJson: String? = null): JSONObject {
        val now = System.currentTimeMillis()
        val base = cachedSnapshot?.takeIf { now - cachedAt < SNAPSHOT_CACHE_MS }?.let {
            JSONObject(it.toString())
        } ?: readSnapshot().also {
            cachedAt = now
            cachedSnapshot = JSONObject(it.toString())
        }
        return mergeDatapathUsage(base, datapathStatsJson)
    }

    private fun mergeDatapathUsage(snapshot: JSONObject, statsJson: String?): JSONObject {
        if (statsJson.isNullOrBlank()) return snapshot
        val statsRoot = runCatching { JSONObject(statsJson) }.getOrNull() ?: return snapshot
        val sessionId = statsRoot.optLong("sessionId", 0L)
        val counters = statsRoot.optJSONArray("clients") ?: return snapshot
        if (sessionId <= 0L) return snapshot
        val byIp = buildMap<String, JSONObject> {
            for (index in 0 until counters.length()) {
                val row = counters.optJSONObject(index) ?: continue
                val ip = row.optString("ipAddress").takeIf { it.isNotBlank() } ?: continue
                put(ip, row)
            }
        }
        val clients = snapshot.optJSONArray("clients") ?: JSONArray()
        var matched = 0
        for (index in 0 until clients.length()) {
            val row = clients.optJSONObject(index) ?: continue
            val ip = row.optString("ipAddress").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val mac = row.optString("macAddress").lowercase(Locale.ROOT)
            val usage = byIp[ip]
            val currentRx = usage?.optLong("downloadBytes") ?: 0L
            val currentTx = usage?.optLong("uploadBytes") ?: 0L
            val stored = runCatching {
                JSONObject(usagePreferences.getString(mac, "{}").orEmpty())
            }.getOrDefault(JSONObject())
            val sameSessionAndIp = stored.optLong("sessionId", -1L) == sessionId &&
                stored.optString("ipAddress") == ip
            val previousRx = stored.optLong("lastRxBytes", 0L)
            val previousTx = stored.optLong("lastTxBytes", 0L)
            val totalRx = stored.optLong("totalRxBytes", 0L) +
                if (sameSessionAndIp && currentRx >= previousRx) currentRx - previousRx else 0L
            val totalTx = stored.optLong("totalTxBytes", 0L) +
                if (sameSessionAndIp && currentTx >= previousTx) currentTx - previousTx else 0L
            usagePreferences.edit().putString(mac, JSONObject().apply {
                put("sessionId", sessionId)
                put("ipAddress", ip)
                put("lastRxBytes", currentRx)
                put("lastTxBytes", currentTx)
                put("totalRxBytes", totalRx)
                put("totalTxBytes", totalTx)
            }.toString()).apply()
            row.put("rxBytes", totalRx)
            row.put("txBytes", totalTx)
            row.put("sessionRxBytes", currentRx)
            row.put("sessionTxBytes", currentTx)
            row.put("usageAvailable", true)
            row.put("usageScope", "userspace_payload_cumulative_by_mac")
            matched++
        }
        snapshot.put("usageAvailable", matched > 0)
        snapshot.put("message", if (matched > 0)
            "Cumulative per-device payload totals are saved locally by MAC; matching depends on the hotspot ARP table"
            else "Datapath counters are active, but no connected client currently has a resolvable IP-to-MAC mapping")
        return snapshot
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
