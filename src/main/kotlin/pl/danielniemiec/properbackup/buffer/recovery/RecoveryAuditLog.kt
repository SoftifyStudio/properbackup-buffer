package pl.danielniemiec.properbackup.buffer.recovery

import pl.danielniemiec.properbackup.buffer.db.Database

class RecoveryAuditLog(private val db: Database) {

    fun log(sessionId: String, action: String, detail: String? = null) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO recovery_audit_log (recovery_session_id, action, detail) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, action)
                if (detail != null) ps.setString(3, detail) else ps.setNull(3, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
    }

    fun listForSession(sessionId: String, limit: Int = 200): List<Map<String, Any?>> {
        val lim = limit.coerceIn(1, 1000)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, action, detail, created_at FROM recovery_audit_log WHERE recovery_session_id = ? ORDER BY created_at LIMIT ?",
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.setInt(2, lim)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            mapOf(
                                "id" to rs.getLong("id"),
                                "action" to rs.getString("action"),
                                "detail" to rs.getString("detail"),
                                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }
}
