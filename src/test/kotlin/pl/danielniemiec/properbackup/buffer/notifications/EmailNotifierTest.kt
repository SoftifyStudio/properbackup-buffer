package pl.danielniemiec.properbackup.buffer.notifications

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class EmailNotifierTest {

    // --- fromEnv defaults ---

    @Test
    fun `fromEnv returns notifier with defaults when env vars not set`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")
        assertNotNull(notifier)
    }

    // --- Unconfigured SMTP: logs instead of sending ---

    @Test
    fun `sendAgentOfflineAlert logs when SMTP not configured`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert(
                to = "user@example.com",
                serverName = "prod-server",
                serverId = "srv-123",
                lastSeen = Instant.parse("2025-01-15T10:30:00Z"),
            )
        }

        assertTrue(stdout.contains("[EmailNotifier] SMTP not configured"), "Should log SMTP not configured")
        assertTrue(stdout.contains("user@example.com"), "Should log recipient")
        assertTrue(stdout.contains("[ProperBackup] Agent OFFLINE: prod-server"), "Should log subject with server name")
    }

    @Test
    fun `sendCustomAlert logs when SMTP not configured`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")

        val stdout = captureStdout {
            notifier.sendCustomAlert(
                to = "admin@example.com",
                subject = "Custom Alert",
                body = "Something happened",
            )
        }

        assertTrue(stdout.contains("[EmailNotifier] SMTP not configured"))
        assertTrue(stdout.contains("admin@example.com"))
        assertTrue(stdout.contains("Custom Alert"))
    }

    // --- Email body content ---

    @Test
    fun `sendAgentOfflineAlert includes server details in log output`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")
        val lastSeen = Instant.parse("2025-06-15T14:30:00Z")

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert(
                to = "test@test.com",
                serverName = "my-vps",
                serverId = "abc-def-123",
                lastSeen = lastSeen,
            )
        }

        assertTrue(stdout.contains("my-vps"), "Body should contain server name")
        assertTrue(stdout.contains("abc-def-123"), "Body should contain server ID")
    }

    // --- Configured SMTP: connection failure is caught ---

    @Test
    fun `send with invalid SMTP host does not throw`() {
        val notifier = EmailNotifier(
            smtpHost = "invalid.smtp.host.test",
            smtpPort = 587,
            smtpUser = "user",
            smtpPass = "pass",
            fromAddress = "noreply@properbackup.com",
        )

        val stderr = captureStderr {
            notifier.sendAgentOfflineAlert(
                to = "fail@example.com",
                serverName = "test-server",
                serverId = "srv-fail",
                lastSeen = Instant.now(),
            )
        }

        assertTrue(stderr.contains("[EmailNotifier] Failed to send email"), "Should log failure to stderr")
    }

    // --- Blank host treated as unconfigured ---

    @Test
    fun `blank SMTP host treated as unconfigured`() {
        val notifier = EmailNotifier("", 587, null, null, "noreply@properbackup.com")

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert(
                to = "user@example.com",
                serverName = "srv",
                serverId = "id",
                lastSeen = Instant.now(),
            )
        }

        assertTrue(stdout.contains("[EmailNotifier] SMTP not configured"))
    }

    @Test
    fun `whitespace-only SMTP host treated as unconfigured`() {
        val notifier = EmailNotifier("   ", 587, null, null, "noreply@properbackup.com")

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert(
                to = "user@example.com",
                serverName = "srv",
                serverId = "id",
                lastSeen = Instant.now(),
            )
        }

        assertTrue(stdout.contains("[EmailNotifier] SMTP not configured"))
    }

    // --- Date formatting ---

    @Test
    fun `alert subject contains server name`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert(
                to = "test@test.com",
                serverName = "Serwer-Produkcyjny",
                serverId = "s-1",
                lastSeen = Instant.now(),
            )
        }

        assertTrue(stdout.contains("[ProperBackup] Agent OFFLINE: Serwer-Produkcyjny"))
    }

    @Test
    fun `alert body uses Europe Warsaw timezone`() {
        val notifier = EmailNotifier(null, 587, null, null, "noreply@properbackup.com")
        val lastSeen = Instant.parse("2025-06-15T12:00:00Z")

        val expectedStr = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.of("Europe/Warsaw"))
            .format(lastSeen)

        val stdout = captureStdout {
            notifier.sendAgentOfflineAlert("test@test.com", "srv", "id", lastSeen)
        }

        assertTrue(stdout.contains(expectedStr), "Should format lastSeen in Europe/Warsaw zone: $expectedStr")
    }

    // --- Helpers ---

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return baos.toString()
    }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val baos = ByteArrayOutputStream()
        System.setErr(PrintStream(baos))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return baos.toString()
    }
}
