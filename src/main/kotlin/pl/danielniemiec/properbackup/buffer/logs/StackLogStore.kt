package pl.danielniemiec.properbackup.buffer.logs

import com.google.gson.Gson
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class StackLogStore(private val db: Database) {
    private val gson = Gson()

    fun ingest(req: LogIngestReq, dropped: Int = 0) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stack_logs(machine_id, component, event_code, level, message, meta_json, dropped, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, req.machineId)
                ps.setString(2, req.component ?: "Agent")
                ps.setInt(3, req.eventCode ?: -1)
                ps.setString(4, (req.level ?: "INFO").uppercase())
                ps.setString(5, req.message ?: "")
                ps.setString(6, gson.toJson(req.meta ?: emptyMap<String, Any?>()))
                ps.setInt(7, dropped)
                ps.setTimestamp(8, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun list(
        machineId: String?,
        component: String?,
        eventCode: Int?,
        level: String?,
        since: Instant?,
        limit: Int = 500,
    ): List<Map<String, Any?>> {
        val sql = StringBuilder(
            "SELECT id, machine_id, component, event_code, level, message, meta_json, dropped, created_at " +
                "FROM stack_logs WHERE 1=1",
        )
        val params = ArrayList<Any>()
        if (!machineId.isNullOrBlank()) {
            sql.append(" AND machine_id = ?")
            params.add(machineId.trim())
        }
        if (!component.isNullOrBlank()) {
            sql.append(" AND component = ?")
            params.add(component.trim())
        }
        if (eventCode != null) {
            sql.append(" AND event_code = ?")
            params.add(eventCode)
        }
        if (!level.isNullOrBlank()) {
            sql.append(" AND level = ?")
            params.add(level.trim().uppercase())
        }
        if (since != null) {
            sql.append(" AND created_at > ?")
            params.add(Timestamp.from(since))
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?")
        params.add(limit.coerceIn(1, 2000))

        db.getConnection().use { conn ->
            conn.prepareStatement(sql.toString()).use { ps ->
                params.forEachIndexed { idx, p -> ps.setObject(idx + 1, p) }
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "id" to rs.getLong("id"),
                                "machineId" to rs.getString("machine_id"),
                                "component" to rs.getString("component"),
                                "eventCode" to rs.getInt("event_code"),
                                "level" to rs.getString("level"),
                                "message" to rs.getString("message"),
                                "meta" to rs.getString("meta_json"),
                                "dropped" to rs.getInt("dropped"),
                                "createdAt" to rs.getTimestamp("created_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun deleteOlderThan(days: Int): Int {
        db.getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM stack_logs WHERE created_at < NOW() - (?::text || ' days')::interval").use { ps ->
                ps.setInt(1, days)
                return ps.executeUpdate()
            }
        }
    }
}
