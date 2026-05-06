package pl.danielniemiec.properbackup.buffer.logs

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class AgentStatusStore(private val db: Database) {

    fun upsert(machineId: String, phase: String, detail: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_agent_status(machine_id, phase, detail, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (machine_id) DO UPDATE SET phase = EXCLUDED.phase, " +
                    "detail = EXCLUDED.detail, updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setString(2, phase)
                if (detail.isNullOrBlank()) {
                    ps.setNull(3, java.sql.Types.VARCHAR)
                } else {
                    ps.setString(3, detail)
                }
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun get(machineId: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT machine_id, phase, detail, updated_at FROM machine_agent_status WHERE machine_id = ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return linkedMapOf(
                        "machineId" to rs.getString("machine_id"),
                        "phase" to rs.getString("phase"),
                        "detail" to rs.getString("detail"),
                        "updatedAt" to rs.getTimestamp("updated_at").toInstant().toString(),
                    )
                }
            }
        }
    }
}
