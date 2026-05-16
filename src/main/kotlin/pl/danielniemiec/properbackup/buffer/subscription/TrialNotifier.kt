package pl.danielniemiec.properbackup.buffer.subscription

import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.email.EmailService
import pl.danielniemiec.properbackup.buffer.email.EmailTemplates
import pl.danielniemiec.properbackup.buffer.logs.LogIngestReq
import pl.danielniemiec.properbackup.buffer.logs.StackLogStore
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

class TrialNotifier(
    private val userStore: UserStore,
    private val logStore: StackLogStore,
    private val emailService: EmailService? = null,
) {
    private val thresholds = listOf(14, 7, 2, 1)

    fun start() {
        thread(name = "trial-notifier", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60_000 * 60) // check every hour
                    checkAndNotify()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("[TrialNotifier] error: ${e.message}")
                }
            }
        }
    }

    fun checkAndNotify() {
        val users = userStore.listTrialUsersNeedingNotification()
        val now = Instant.now()
        for (user in users) {
            val expiresAt = Instant.parse(user.trialExpiresAt ?: continue)
            val daysLeft = Duration.between(now, expiresAt).toDays().toInt()
            val marker = thresholds.firstOrNull { daysLeft <= it } ?: continue
            val expectedMarker = "${marker}d"

            if (user.lastTrialNotification == expectedMarker) continue

            logStore.ingest(
                LogIngestReq(
                    machineId = "system",
                    component = "TrialNotifier",
                    eventCode = -300,
                    level = "INFO",
                    message = "Trial expiring in $daysLeft days for ${user.email}",
                    meta = mapOf(
                        "userId" to user.id,
                        "email" to user.email,
                        "daysLeft" to daysLeft,
                        "marker" to expectedMarker,
                    ),
                ),
            )
            userStore.updateLastTrialNotification(user.id, expectedMarker)
            println("[TrialNotifier] Notification sent: ${user.email} — $daysLeft days left (marker=$expectedMarker)")

            if (emailService != null) {
                val subject = if (daysLeft <= 0) {
                    "Your ProperBackup trial has expired"
                } else {
                    "ProperBackup trial expires in $daysLeft day${if (daysLeft != 1) "s" else ""}"
                }
                val html = if (daysLeft <= 0) {
                    EmailTemplates.trialExpired(user.email)
                } else {
                    EmailTemplates.trialExpiring(daysLeft, user.email)
                }
                emailService.send(user.email, subject, html)
            }
        }
    }
}
