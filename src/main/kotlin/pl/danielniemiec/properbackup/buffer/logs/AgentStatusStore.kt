package pl.danielniemiec.properbackup.buffer.logs

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class AgentStatusStore(private val db: Database) {

    fun upsert(machineId: String, phase: String, detail: String?, totalFiles: Int?, scannedFiles: Int?, currentFile: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_agent_status(machine_id, phase, detail, total_files, scanned_files, current_file, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (machine_id) DO UPDATE SET phase = EXCLUDED.phase, " +
                    "detail = EXCLUDED.detail, total_files = EXCLUDED.total_files, " +
                    "scanned_files = EXCLUDED.scanned_files, current_file = EXCLUDED.current_file, " +
                    "updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setString(2, phase)
                if (detail.isNullOrBlank()) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, detail)
                if (totalFiles == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, totalFiles)
                if (scannedFiles == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, scannedFiles)
                if (currentFile.isNullOrBlank()) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, currentFile)
                ps.setTimestamp(7, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun get(machineId: String): Map<String, Any?>? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT machine_id, phase, detail, total_files, scanned_files, current_file, updated_at FROM machine_agent_status WHERE machine_id = ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val tf = rs.getInt("total_files")
                    val tfNull = rs.wasNull()
                    val sf = rs.getInt("scanned_files")
                    val sfNull = rs.wasNull()
                    return linkedMapOf(
                        "machineId" to rs.getString("machine_id"),
                        "phase" to rs.getString("phase"),
                        "detail" to rs.getString("detail"),
                        "totalFiles" to if (tfNull) null else tf,
                        "scannedFiles" to if (sfNull) null else sf,
                        "currentFile" to rs.getString("current_file"),
                        "updatedAt" to rs.getTimestamp("updated_at").toInstant().toString(),
                    )
                }
            }
        }
    }
}
