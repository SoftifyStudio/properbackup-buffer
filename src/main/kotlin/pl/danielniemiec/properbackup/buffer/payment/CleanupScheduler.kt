package pl.danielniemiec.properbackup.buffer.payment

import pl.danielniemiec.properbackup.buffer.db.Database
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scheduled cleanup tasks per §9.7:
 * - stripe_event_idempotency: 90-day TTL
 * - stripe_webhook_dlq (resolved): 30-day TTL
 * - abandoned users (never paid, 30+ days): cleanup with audit trail
 */
class CleanupScheduler(
    private val db: Database,
    private val webhookIdempotency: WebhookIdempotency,
    private val webhookDlq: WebhookDlq,
) {
    private val log = LoggerFactory.getLogger(CleanupScheduler::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cleanup-scheduler").also { it.isDaemon = true }
    }

    fun start() {
        // Idempotency cleanup: every 24h, 90-day retention
        executor.scheduleAtFixedRate({
            runCatching {
                val deleted = webhookIdempotency.cleanupOldRecords(90)
                if (deleted > 0) log.info("Cleaned {} idempotency records (>90 days)", deleted)
            }.onFailure { log.error("Idempotency cleanup failed", it) }
        }, 1, 24, TimeUnit.HOURS)

        // DLQ resolved cleanup: every 24h, 30-day retention
        executor.scheduleAtFixedRate({
            runCatching {
                val deleted = webhookDlq.cleanupResolved(30)
                if (deleted > 0) log.info("Cleaned {} resolved DLQ entries (>30 days)", deleted)
            }.onFailure { log.error("DLQ cleanup failed", it) }
        }, 2, 24, TimeUnit.HOURS)

        // Abandoned users cleanup: every 24h, 30-day threshold
        executor.scheduleAtFixedRate({
            runCatching {
                val deleted = cleanupAbandonedUsers()
                if (deleted > 0) log.info("Cleaned {} abandoned users (>30 days, never paid)", deleted)
            }.onFailure { log.error("Abandoned users cleanup failed", it) }
        }, 3, 24, TimeUnit.HOURS)

        log.info("Cleanup scheduler started (idempotency=90d, dlq=30d, abandoned=30d)")
    }

    fun stop() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    /**
     * Per §8.8: users with no subscription_plan, no trial, no payment, created >30 days ago.
     * Before deletion, copies to subscription_audit_log for compliance.
     */
    private fun cleanupAbandonedUsers(): Int {
        return db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Log the cleanup to audit trail before deletion
                val insertAudit = conn.prepareStatement(
                    """INSERT INTO subscription_audit_log (user_id, action, detail)
                       SELECT id, 'abandoned_user_cleanup', 'email=' || email
                       FROM users
                       WHERE subscription_plan IS NULL
                         AND trial_expires_at IS NULL
                         AND created_at < NOW() - INTERVAL '30 days'"""
                )
                insertAudit.executeUpdate()

                // Delete abandoned users
                val deleteStmt = conn.prepareStatement(
                    """DELETE FROM users
                       WHERE subscription_plan IS NULL
                         AND trial_expires_at IS NULL
                         AND created_at < NOW() - INTERVAL '30 days'"""
                )
                val deleted = deleteStmt.executeUpdate()
                conn.commit()
                deleted
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
