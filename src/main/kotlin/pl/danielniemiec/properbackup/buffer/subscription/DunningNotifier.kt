package pl.danielniemiec.properbackup.buffer.subscription

import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.payment.SubscriptionAuditLog
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/**
 * DunningNotifier — sends escalating email notifications during payment failure.
 * Per master-tdd-plan.md §9.4 [TDD-H1].
 *
 * Email sequence after invoice.payment_failed:
 * - T+0: "Problem z platnoscia. Ponowimy automatycznie"
 * - T+3 days: "Nadal nie udalo sie pobrac. Aktualizuj karte"
 * - T+14 days: "Ostatnie wezwanie. Po 30 dniach konto wygasnie"
 * - On subscription.deleted: "Subskrypcja zamknieta. Dane bezpieczne przez 30 dni"
 *
 * Idempotency: last_past_due_notification column tracks which email was last sent.
 * Values: 'past_due_t0', 'past_due_t3', 'past_due_t14', 'final_cancellation'
 */
class DunningNotifier(
    private val db: Database,
    private val userStore: UserStore,
    private val auditLog: SubscriptionAuditLog,
    private val mailer: Mailer = LoggingMailer(),
) {
    interface Mailer {
        fun send(to: String, subject: String, body: String)
    }

    class LoggingMailer : Mailer {
        val sentEmails = mutableListOf<Triple<String, String, String>>()
        override fun send(to: String, subject: String, body: String) {
            sentEmails.add(Triple(to, subject, body))
            println("[DunningNotifier] EMAIL to=$to subject=$subject")
        }
    }

    companion object {
        const val MARKER_T0 = "past_due_t0"
        const val MARKER_T3 = "past_due_t3"
        const val MARKER_T14 = "past_due_t14"
        const val MARKER_FINAL = "final_cancellation"

        val THRESHOLD_T3 = Duration.ofDays(3)
        val THRESHOLD_T14 = Duration.ofDays(14)
    }

    fun start() {
        thread(name = "dunning-notifier", isDaemon = true) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60_000 * 60) // check every hour
                    runDailyCheck()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("[DunningNotifier] error: ${e.message}")
                }
            }
        }
    }

    fun runDailyCheck(now: Instant = Instant.now()) {
        val users = userStore.listPastDueUsersNeedingNotification()
        for (user in users) {
            val pastDueStart = Instant.parse(user.pastDueStartedAt ?: continue)
            val elapsed = Duration.between(pastDueStart, now)

            val targetMarker = when {
                elapsed >= THRESHOLD_T14 -> MARKER_T14
                elapsed >= THRESHOLD_T3 -> MARKER_T3
                else -> MARKER_T0
            }

            if (user.lastPastDueNotification == targetMarker) continue

            val (subject, body) = getEmailContent(targetMarker)
            mailer.send(user.email, subject, body)
            userStore.updateLastPastDueNotification(user.id, targetMarker)
            auditLog.append(user.id, "dunning_email_sent", "marker=$targetMarker")
            println("[DunningNotifier] Sent $targetMarker to ${user.email} (elapsed=${elapsed.toDays()}d)")
        }
    }

    fun sendImmediateT0(userId: String, email: String) {
        val (subject, body) = getEmailContent(MARKER_T0)
        mailer.send(email, subject, body)
        userStore.updateLastPastDueNotification(userId, MARKER_T0)
        auditLog.append(userId, "dunning_email_sent", "marker=$MARKER_T0")
    }

    fun sendFinalCancellation(userId: String, email: String) {
        val (subject, body) = getEmailContent(MARKER_FINAL)
        mailer.send(email, subject, body)
        userStore.updateLastPastDueNotification(userId, MARKER_FINAL)
        auditLog.append(userId, "dunning_email_sent", "marker=$MARKER_FINAL")
    }

    private fun getEmailContent(marker: String): Pair<String, String> = when (marker) {
        MARKER_T0 -> Pair(
            "Problem z platnoscia za ProperBackup",
            "Nie udalo sie pobrac platnosci. Twoj backup nadal dziala. Ponowimy automatycznie."
        )
        MARKER_T3 -> Pair(
            "Ponawiamy probe pobrania platnosci",
            "Nadal nie udalo sie pobrac platnosci. Aktualizuj karte, aby uniknac przerwy w backupie."
        )
        MARKER_T14 -> Pair(
            "Ostatnie wezwanie — ProperBackup",
            "Ostatnie wezwanie. Jesli nie zaktualizujesz karty, po 30 dniach od pierwszego problemu konto wygasnie."
        )
        MARKER_FINAL -> Pair(
            "Twoja subskrypcja ProperBackup zostala zamknieta",
            "Subskrypcja zamknieta. Twoje dane sa bezpieczne przez 30 dni — mozesz je odzyskac w panelu."
        )
        else -> Pair("ProperBackup — powiadomienie", "Skontaktuj sie z nami.")
    }
}
