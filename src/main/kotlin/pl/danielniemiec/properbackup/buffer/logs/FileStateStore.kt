package pl.danielniemiec.properbackup.buffer.logs

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class FileStateStore(private val db: Database) {

    fun getCurrentStatus(machineId: String, fullPath: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT status FROM machine_file_state WHERE machine_id = ? AND full_path = ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setString(2, fullPath)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("status")
                    return null
                }
            }
        }
    }

    fun countForMachine(machineId: String): Long {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*)::BIGINT FROM machine_file_state WHERE machine_id = ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getLong(1)
                    return 0L
                }
            }
        }
    }

    fun listPaged(machineId: String, offset: Int, limit: Int, sortBy: String = "path"): List<Map<String, Any?>> {
        val orderClause = if (sortBy == "recent") "ORDER BY updated_at DESC" else "ORDER BY full_path ASC"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT machine_id, full_path, status, updated_at FROM machine_file_state WHERE machine_id = ? " +
                    "$orderClause OFFSET ? LIMIT ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setInt(2, offset.coerceAtLeast(0))
                ps.setInt(3, limit.coerceIn(1, 10000))
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "machineId" to rs.getString("machine_id"),
                                "fullPath" to rs.getString("full_path"),
                                "status" to rs.getString("status"),
                                "updatedAt" to rs.getTimestamp("updated_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun upsert(req: FileStateReq) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_file_state(machine_id, full_path, status, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(machine_id, full_path) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                ps.setString(1, req.machineId)
                ps.setString(2, req.fullPath)
                ps.setString(3, req.status ?: "current")
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun upsertBatch(machineId: String, files: List<FileStateBatchEntry>) {
        if (files.isEmpty()) return
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_file_state(machine_id, full_path, status, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(machine_id, full_path) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                val now = Timestamp.from(Instant.now())
                for (f in files) {
                    if (f.fullPath.isNullOrBlank()) continue
                    ps.setString(1, machineId)
                    ps.setString(2, f.fullPath)
                    ps.setString(3, f.status ?: "current")
                    ps.setTimestamp(4, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun deleteOlderThan(days: Int): Int {
        db.getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM machine_file_state WHERE updated_at < NOW() - (?::text || ' days')::interval").use { ps ->
                ps.setInt(1, days)
                return ps.executeUpdate()
            }
        }
    }
}
