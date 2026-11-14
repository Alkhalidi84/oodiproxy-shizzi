package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactorTest {
    @Test fun redactsJsonCredentials() {
        assertEquals(
            """{"privateKey":"<redacted>","password":"<redacted>","name":"home"}""",
            LogRedactor.redact("""{"privateKey":"abc123","password":"secret","name":"home"}"""),
        )
    }

    @Test fun redactsUrlUserInfoAndQuerySecrets() {
        assertEquals(
            "vless://<redacted>@example.com:443?token=<redacted>&type=ws",
            LogRedactor.redact("vless://uuid-secret@example.com:443?token=abc&type=ws"),
        )
    }

    @Test fun preservesNonSecretDiagnostics() {
        val message = "startDatapath failed: fd=88 mtu=1500"
        assertEquals(message, LogRedactor.redact(message))
    }
}
