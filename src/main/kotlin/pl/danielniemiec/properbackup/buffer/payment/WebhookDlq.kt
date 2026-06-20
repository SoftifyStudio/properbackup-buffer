package pl.danielniemiec.properbackup.buffer.payment

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Dead-Letter Queue for Stripe webhooks that fail processing.
 * Per [TDD-B2] §9.1: after N retries, event goes to DLQ for manual replay.
 *
 * - Events stay in DLQ until manually resolved or replayed by admin.
 * - Resolved entries are cleaned up after 30 days (by cleanup cron).
 * - Admin endpoint: POST /admin/webhooks/dlq/{id}/replay
 */
class WebhookDlq(private val db: Database) {

    data class DlqEntry(
        val id: Long,
        val stripeEventId: String,
        val eventType: String,
        val payload: String,
        val signatureHeader: String,
        val retryCount: Int,
        val lastError: String?,
        val firstSeenAt: Instant,
        val lastAttemptAt: Instant,
        val resolvedAt: Instant?,
    )

    fun enqueue(
        eventId: String,
        eventType: String,
        payload: String,
        signatureHeader: String,
        error: Throwable,
    ) {
        db.getConnection().use { conn ->
            // Upsert: if already in DLQ, increment retry count
            val existing = conn.prepareStatement(
                "SELECT id, retry_count FROM stripe_webhook_dlq WHERE stripe_event_id = ? AND resolved_at IS NULL"
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("id") to rs.getInt("retry_count") else null
                }
            }

            if (existing != null) {
                conn.prepareStatement(
                    "UPDATE stripe_webhook_dlq SET retry_count = ?, last_error = ?, last_attempt_at = NOW() WHERE id = ?"
                ).use { stmt ->
                    stmt.setInt(1, existing.second + 1)
                    stmt.setString(2, error.message?.take(2000))
                    stmt.setLong(3, existing.first)
                    stmt.executeUpdate()
                }
            } else {
                conn.prepareStatement(
                    """INSERT INTO stripe_webhook_dlq 
                       (stripe_event_id, event_type, payload, signature_header, last_error)
                       VALUES (?, ?, ?, ?, ?)"""
                ).use { stmt ->
                    stmt.setString(1, eventId)
                    stmt.setString(2, eventType)
                    stmt.setString(3, payload)
                    stmt.setString(4, signatureHeader)
                    stmt.setString(5, error.message?.take(2000))
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun listUnresolved(limit: Int = 100): List<DlqEntry> {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM stripe_webhook_dlq WHERE resolved_at IS NULL ORDER BY first_seen_at DESC LIMIT ?"
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<DlqEntry>()
                    while (rs.next()) {
                        entries.add(mapRow(rs))
                    }
                    entries
                }
            }
        }
    }

    fun getById(id: Long): DlqEntry? {
        return db.getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM stripe_webhook_dlq WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    fun markResolved(id: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE stripe_webhook_dlq SET resolved_at = NOW() WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun cleanupResolved(olderThanDays: Int = 30): Int {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM stripe_webhook_dlq WHERE resolved_at IS NOT NULL AND resolved_at < NOW() - INTERVAL '1 day' * ?"
            ).use { stmt ->
                stmt.setInt(1, olderThanDays)
                stmt.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): DlqEntry {
        return DlqEntry(
            id = rs.getLong("id"),
            stripeEventId = rs.getString("stripe_event_id"),
            eventType = rs.getString("event_type"),
            payload = rs.getString("payload"),
            signatureHeader = rs.getString("signature_header"),
            retryCount = rs.getInt("retry_count"),
            lastError = rs.getString("last_error"),
            firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
            lastAttemptAt = rs.getTimestamp("last_attempt_at").toInstant(),
            resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
        )
    }
}
