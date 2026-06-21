package pl.danielniemiec.properbackup.buffer.notifications

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * Sends email notifications for ProperBackup alerts.
 * Uses SMTP (Jakarta/JavaMail). Falls back to logging if SMTP not configured.
 */
open class EmailNotifier(
    private val smtpHost: String?,
    private val smtpPort: Int,
    private val smtpUser: String?,
    private val smtpPass: String?,
    private val fromAddress: String,
) {
    private val configured: Boolean = !smtpHost.isNullOrBlank()

    open fun sendAgentOfflineAlert(to: String, serverName: String, serverId: String, lastSeen: Instant) {
        val lastSeenStr = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.of("Europe/Warsaw"))
            .format(lastSeen)

        val subject = "[ProperBackup] Agent OFFLINE: $serverName"
        val body = """
            |Serwer "$serverName" (ID: $serverId) jest OFFLINE.
            |
            |Ostatni kontakt agenta: $lastSeenStr
            |
            |Agent nie odpowiada od ponad 30 minut. Sprawdz czy proces agenta dziala
            |na serwerze docelowym.
            |
            |Mozliwe przyczyny:
            |  - Proces agenta zostal zatrzymany lub zabity
            |  - Serwer jest niedostepny (restart, awaria)
            |  - Problem sieciowy (firewall, DNS)
            |
            |Aby sprawdzic status:
            |  ssh root@<server> systemctl status properbackup-agent
            |
            |--
            |ProperBackup Monitoring
        """.trimMargin()

        send(to, subject, body)
    }

    fun sendCustomAlert(to: String, subject: String, body: String) {
        send(to, subject, body)
    }

    private fun send(to: String, subject: String, body: String) {
        if (!configured) {
            println("[EmailNotifier] SMTP not configured, logging instead:")
            println("[EmailNotifier]   To: $to")
            println("[EmailNotifier]   Subject: $subject")
            println("[EmailNotifier]   Body: ${body.take(200)}...")
            return
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(smtpUser, smtpPass)
            })

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(fromAddress, "ProperBackup"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                setText(body, "UTF-8")
            }

            Transport.send(msg)
            println("[EmailNotifier] Sent to $to: $subject")
        } catch (e: Exception) {
            System.err.println("[EmailNotifier] Failed to send email to $to: ${e.message}")
        }
    }

    companion object {
        fun fromEnv(): EmailNotifier {
            val host = System.getenv("SMTP_HOST")
            val port = System.getenv("SMTP_PORT")?.toIntOrNull() ?: 587
            val user = System.getenv("SMTP_USER")
            val pass = System.getenv("SMTP_PASS")
            val from = System.getenv("SMTP_FROM") ?: "noreply@properbackup.com"
            return EmailNotifier(host, port, user, pass, from)
        }
    }
}
