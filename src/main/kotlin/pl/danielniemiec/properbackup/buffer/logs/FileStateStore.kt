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
        val now = Timestamp.from(Instant.now())
        val status = req.status ?: "current"
        val mid = req.machineId ?: return
        val fp = req.fullPath ?: return
        db.getConnection().use { conn ->
            val previousStatus = getCurrentStatus(mid, fp)
            conn.prepareStatement(
                "INSERT INTO machine_file_state(machine_id, full_path, status, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(machine_id, full_path) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                ps.setString(1, mid)
                ps.setString(2, fp)
                ps.setString(3, status)
                ps.setTimestamp(4, now)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO machine_file_event(machine_id, full_path, status, previous_status, occurred_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, mid)
                ps.setString(2, fp)
                ps.setString(3, status)
                if (previousStatus != null) ps.setString(4, previousStatus) else ps.setNull(4, java.sql.Types.VARCHAR)
                ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
        }
    }

    fun upsertBatch(machineId: String, files: List<FileStateBatchEntry>) {
        if (files.isEmpty()) return
        db.getConnection().use { conn ->
            val now = Timestamp.from(Instant.now())
            conn.prepareStatement(
                "INSERT INTO machine_file_state(machine_id, full_path, status, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(machine_id, full_path) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at",
            ).use { ps ->
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
            conn.prepareStatement(
                "INSERT INTO machine_file_event(machine_id, full_path, status, occurred_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
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

    /**
     * Returns file states as they were at a given point in time.
     * Reconstructs state from machine_file_event (append-only log).
     * Falls back to current machine_file_state if no events exist yet.
     */
    fun listAtTimestamp(machineId: String, timestamp: Instant, offset: Int, limit: Int, sortBy: String = "path"): List<Map<String, Any?>> {
        val orderClause = if (sortBy == "recent") "ORDER BY occurred_at DESC" else "ORDER BY full_path ASC"
        val ts = Timestamp.from(timestamp)
        db.getConnection().use { conn ->
            val hasEvents = conn.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM machine_file_event WHERE machine_id = ? AND occurred_at <= ?)",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setTimestamp(2, ts)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (!hasEvents) {
                val fallbackOrder = if (sortBy == "recent") "ORDER BY updated_at DESC" else "ORDER BY full_path ASC"
                return conn.prepareStatement(
                    "SELECT machine_id, full_path, status, updated_at FROM machine_file_state " +
                        "WHERE machine_id = ? $fallbackOrder OFFSET ? LIMIT ?",
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
                        out
                    }
                }
            }
            return conn.prepareStatement(
                "SELECT machine_id, full_path, status, occurred_at FROM (" +
                    "SELECT DISTINCT ON (machine_id, full_path) machine_id, full_path, status, occurred_at " +
                    "FROM machine_file_event WHERE machine_id = ? AND occurred_at <= ? " +
                    "ORDER BY machine_id, full_path, occurred_at DESC" +
                ") sub WHERE status != 'deleted' $orderClause OFFSET ? LIMIT ?",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setTimestamp(2, ts)
                ps.setInt(3, offset.coerceAtLeast(0))
                ps.setInt(4, limit.coerceIn(1, 10000))
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "machineId" to rs.getString("machine_id"),
                                "fullPath" to rs.getString("full_path"),
                                "status" to rs.getString("status"),
                                "updatedAt" to rs.getTimestamp("occurred_at").toInstant().toString(),
                            ),
                        )
                    }
                    out
                }
            }
        }
    }

    fun countAtTimestamp(machineId: String, timestamp: Instant): Long {
        val ts = Timestamp.from(timestamp)
        db.getConnection().use { conn ->
            val hasEvents = conn.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM machine_file_event WHERE machine_id = ? AND occurred_at <= ?)",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setTimestamp(2, ts)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (!hasEvents) {
                return conn.prepareStatement(
                    "SELECT COUNT(*)::BIGINT FROM machine_file_state WHERE machine_id = ?",
                ).use { ps ->
                    ps.setString(1, machineId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                }
            }
            return conn.prepareStatement(
                "SELECT COUNT(*)::BIGINT FROM (" +
                    "SELECT DISTINCT ON (machine_id, full_path) status " +
                    "FROM machine_file_event WHERE machine_id = ? AND occurred_at <= ? " +
                    "ORDER BY machine_id, full_path, occurred_at DESC" +
                ") sub WHERE status != 'deleted'",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setTimestamp(2, ts)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
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
