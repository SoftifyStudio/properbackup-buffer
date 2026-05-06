package pl.danielniemiec.properbackup.buffer.logs

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class MachineFileEventStore(private val db: Database) {

    fun insert(machineId: String, fullPath: String, status: String, previousStatus: String?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_file_event(machine_id, full_path, status, previous_status, occurred_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.setString(2, fullPath)
                ps.setString(3, status)
                if (previousStatus == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, previousStatus)
                ps.setTimestamp(5, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun listByExactPath(machineId: String, fullPath: String, limit: Int): List<Map<String, Any?>> =
        listInternal(machineId, "full_path = ?", fullPath, limit, 0)

    fun listByPrefix(machineId: String, prefix: String, limit: Int, offset: Int): List<Map<String, Any?>> =
        listInternal(machineId, "starts_with(full_path, ?)", prefix, limit, offset)

    private fun listInternal(
        machineId: String,
        whereExpr: String,
        pathParam: String,
        limit: Int,
        offset: Int,
    ): List<Map<String, Any?>> {
        val lim = limit.coerceIn(1, 10_000)
        val off = offset.coerceAtLeast(0)
        val sql = "SELECT id, machine_id, full_path, status, previous_status, occurred_at FROM machine_file_event " +
            "WHERE machine_id = ? AND $whereExpr ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
        return db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, machineId)
                ps.setString(2, pathParam)
                ps.setInt(3, lim)
                ps.setInt(4, off)
                ps.executeQuery().use { rs -> mapRows(rs) }
            }
        }
    }

    fun listRecentForMachine(
        machineId: String,
        statusFilter: String?,
        limit: Int,
        offset: Int,
    ): List<Map<String, Any?>> {
        val lim = limit.coerceIn(1, 10_000)
        val off = offset.coerceAtLeast(0)
        val sql =
            if (statusFilter.isNullOrBlank()) {
                "SELECT id, machine_id, full_path, status, previous_status, occurred_at FROM machine_file_event " +
                    "WHERE machine_id = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
            } else {
                "SELECT id, machine_id, full_path, status, previous_status, occurred_at FROM machine_file_event " +
                    "WHERE machine_id = ? AND status = ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
            }
        return db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, machineId)
                if (statusFilter.isNullOrBlank()) {
                    ps.setInt(2, lim)
                    ps.setInt(3, off)
                } else {
                    ps.setString(2, statusFilter)
                    ps.setInt(3, lim)
                    ps.setInt(4, off)
                }
                ps.executeQuery().use { rs -> mapRows(rs) }
            }
        }
    }

    private fun mapRows(rs: java.sql.ResultSet): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        while (rs.next()) {
            out.add(
                linkedMapOf(
                    "id" to rs.getLong("id"),
                    "machineId" to rs.getString("machine_id"),
                    "fullPath" to rs.getString("full_path"),
                    "status" to rs.getString("status"),
                    "previousStatus" to rs.getString("previous_status"),
                    "occurredAt" to rs.getTimestamp("occurred_at").toInstant().toString(),
                ),
            )
        }
        return out
    }

    fun deleteOlderThan(days: Int): Int {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM machine_file_event WHERE occurred_at < NOW() - (?::text || ' days')::interval",
            ).use { ps ->
                ps.setInt(1, days)
                return ps.executeUpdate()
            }
        }
    }
}
