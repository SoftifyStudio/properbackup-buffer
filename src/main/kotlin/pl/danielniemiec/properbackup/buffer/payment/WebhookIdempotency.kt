package pl.danielniemiec.properbackup.buffer.payment

import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Instant

/**
 * Webhook event deduplication and out-of-order protection.
 *
 * - tryClaimStripeEventId: returns true exactly once per event (dedup)
 * - isStaleEvent: returns true if event.created < user.last_stripe_event_at (out-of-order)
 * - updateLastStripeEventAt: advances the user's event timestamp marker
 */
class WebhookIdempotency(private val db: Database) {

    /**
     * Attempts to claim a Stripe event ID for processing.
     * Returns true if this is the first time the event is seen (claim successful).
     * Returns false if event was already processed (duplicate).
     */
    fun tryClaimStripeEventId(eventId: String, eventType: String): Boolean {
        return db.getConnection().use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO stripe_event_idempotency (stripe_event_id, event_type) VALUES (?, ?)"
                ).use { stmt ->
                    stmt.setString(1, eventId)
                    stmt.setString(2, eventType)
                    stmt.executeUpdate()
                }
                true
            } catch (e: org.postgresql.util.PSQLException) {
                // unique_violation = already claimed
                if (e.sqlState == "23505") false else throw e
            }
        }
    }

    /**
     * Checks if a webhook event is stale (out-of-order).
     * Per §8.3: reject events whose created timestamp is older than the last processed event.
     */
    fun isStaleEvent(userId: String, eventCreated: Instant): Boolean {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT last_stripe_event_at FROM users WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val lastEventAt = rs.getTimestamp("last_stripe_event_at")?.toInstant()
                        lastEventAt != null && eventCreated.isBefore(lastEventAt)
                    } else {
                        false
                    }
                }
            }
        }
    }

    /**
     * Advances the user's last_stripe_event_at to the given timestamp.
     * Only updates if the new timestamp is newer than the current value.
     */
    fun updateLastStripeEventAt(userId: String, eventCreated: Instant) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """UPDATE users SET last_stripe_event_at = ? 
                   WHERE id = ? AND (last_stripe_event_at IS NULL OR last_stripe_event_at < ?)"""
            ).use { stmt ->
                stmt.setTimestamp(1, java.sql.Timestamp.from(eventCreated))
                stmt.setString(2, userId)
                stmt.setTimestamp(3, java.sql.Timestamp.from(eventCreated))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Cleanup: delete idempotency records older than 90 days.
     * Per §8.9 / §9.7: prevents unbounded growth of stripe_event_idempotency.
     */
    fun cleanupOldRecords(retentionDays: Int = 90): Int {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM stripe_event_idempotency WHERE processed_at < NOW() - INTERVAL '1 day' * ?"
            ).use { stmt ->
                stmt.setInt(1, retentionDays)
                stmt.executeUpdate()
            }
        }
    }
}
