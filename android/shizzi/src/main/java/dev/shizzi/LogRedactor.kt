package dev.shizzi

/**
 * Redacts credentials before messages reach Logcat, the on-disk session log,
 * or exported diagnostics. This is intentionally conservative: it preserves
 * field names and protocol structure while hiding secret values.
 */
internal object LogRedactor {
    private val assignments = Regex(
        """(?i)(["']?(?:private[_-]?key|password|passwd|token|uuid|authorization|access[_-]?token|refresh[_-]?token|client[_-]?secret)["']?\s*[:=]\s*["']?)([^"'&,\s}\]]+)(["']?)"""
    )
    private val urlUserInfo = Regex("""(?i)(://)[^/@\s]+@""")
    private val querySecrets = Regex(
        """(?i)([?&](?:token|access_token|refresh_token|password|key|secret|uuid)=)[^&#\s]+"""
    )

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        return message
            .replace(urlUserInfo) { match -> match.groupValues[1] + "<redacted>@" }
            .replace(querySecrets) { match -> match.groupValues[1] + "<redacted>" }
            .replace(assignments) { match ->
                match.groupValues[1] + "<redacted>" + match.groupValues[3]
            }
    }
}
