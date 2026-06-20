package pl.danielniemiec.properbackup.buffer.payment

import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Instant

/**
 * Append-only subscription audit log for billing compliance.
 * Per §7 and §10: every billing state change MUST produce an audit entry.
 *
 * This table is NEVER cleaned up (compliance/tax requirement).
 */
class SubscriptionAuditLog(private val db: Database) {

    data class AuditEntry(
        val id: Long,
        val userId: String,
        val action: String,
        val detail: String?,
        val createdAt: Instant,
    )

    fun append(userId: String, action: String, detail: String? = null) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO subscription_audit_log (user_id, action, detail) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, action)
                stmt.setString(3, detail)
                stmt.executeUpdate()
            }
        }
    }

    fun listByUser(userId: String, limit: Int = 100): List<AuditEntry> {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM subscription_audit_log WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<AuditEntry>()
                    while (rs.next()) {
                        entries.add(
                            AuditEntry(
                                id = rs.getLong("id"),
                                userId = rs.getString("user_id"),
                                action = rs.getString("action"),
                                detail = rs.getString("detail"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                        )
                    }
                    entries
                }
            }
        }
    }

    fun countByAction(userId: String, action: String): Int {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM subscription_audit_log WHERE user_id = ? AND action = ?"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, action)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }
}
