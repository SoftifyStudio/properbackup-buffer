package pl.danielniemiec.properbackup.buffer.notifications

import java.util.Properties
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * Sends transactional email alerts.
 * Uses SMTP configured via environment variables.
 * Architecture ready for SMS (future) — add SmsNotifier implementing same interface.
 */
class EmailNotifier(
    private val smtpHost: String = System.getenv("SMTP_HOST") ?: "",
    private val smtpPort: Int = (System.getenv("SMTP_PORT") ?: "587").toIntOrNull() ?: 587,
    private val smtpUser: String = System.getenv("SMTP_USER") ?: "",
    private val smtpPass: String = System.getenv("SMTP_PASS") ?: "",
    private val fromAddress: String = System.getenv("SMTP_FROM") ?: "noreply@properbackup.com",
) {

    private val enabled: Boolean = smtpHost.isNotBlank() && smtpUser.isNotBlank()

    fun sendAlert(to: String, subject: String, bodyHtml: String): Boolean {
        if (!enabled) {
            println("[EmailNotifier] SMTP not configured — skipping email to $to: $subject")
            return false
        }
        return try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort.toString())
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(smtpUser, smtpPass)
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromAddress, "ProperBackup"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                setContent(bodyHtml, "text/html; charset=utf-8")
            }

            Transport.send(message)
            println("[EmailNotifier] Email sent to $to: $subject")
            true
        } catch (e: Exception) {
            System.err.println("[EmailNotifier] Failed to send email to $to: ${e.message}")
            false
        }
    }

    fun sendAgentOfflineAlert(to: String, serverName: String, serverId: String): Boolean {
        val subject = "[ProperBackup] Agent offline: $serverName"
        val body = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 600px;">
                <h2 style="color: #dc2626;">Agent Offline Alert</h2>
                <p>Your backup agent on <strong>$serverName</strong> has not sent a heartbeat for over 30 minutes.</p>
                <p style="color: #6b7280; font-size: 14px;">Server ID: $serverId</p>
                <p>Please check that the agent process is running on this server.</p>
                <hr style="border: none; border-top: 1px solid #e5e7eb; margin: 20px 0;" />
                <p style="color: #9ca3af; font-size: 12px;">ProperBackup — Zero-Knowledge Backup</p>
            </div>
        """.trimIndent()
        return sendAlert(to, subject, body)
    }

    fun sendStorageWarningAlert(to: String, usedPercent: Double, usedLabel: String, limitLabel: String): Boolean {
        val subject = "[ProperBackup] Storage warning: ${usedPercent.toInt()}% used"
        val body = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 600px;">
                <h2 style="color: #d97706;">Storage Warning</h2>
                <p>Your storage usage has reached <strong>${usedPercent.toInt()}%</strong> ($usedLabel / $limitLabel).</p>
                <p>Consider upgrading your plan or removing unnecessary backups.</p>
                <hr style="border: none; border-top: 1px solid #e5e7eb; margin: 20px 0;" />
                <p style="color: #9ca3af; font-size: 12px;">ProperBackup — Zero-Knowledge Backup</p>
            </div>
        """.trimIndent()
        return sendAlert(to, subject, body)
    }

    fun sendRetentionWarningAlert(to: String, daysLeft: Int, deadlineDate: String): Boolean {
        val subject = "[ProperBackup] Data deletion in $daysLeft days"
        val body = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 600px;">
                <h2 style="color: #dc2626;">Data Retention Warning</h2>
                <p>Your subscription has expired. Your backup data will be permanently deleted on <strong>$deadlineDate</strong> ($daysLeft days remaining).</p>
                <p>Renew your subscription to keep your data safe.</p>
                <hr style="border: none; border-top: 1px solid #e5e7eb; margin: 20px 0;" />
                <p style="color: #9ca3af; font-size: 12px;">ProperBackup — Zero-Knowledge Backup</p>
            </div>
        """.trimIndent()
        return sendAlert(to, subject, body)
    }
}
