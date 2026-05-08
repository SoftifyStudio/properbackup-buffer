package pl.danielniemiec.properbackup.buffer.monitoring

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class AgentMetricsStore(private val db: Database) {

    fun insert(serverId: String, cpuPercent: Float, ramUsedMb: Float, ramTotalMb: Float) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO agent_metrics (server_id, cpu_percent, ram_used_mb, ram_total_mb, recorded_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setFloat(2, cpuPercent)
                ps.setFloat(3, ramUsedMb)
                ps.setFloat(4, ramTotalMb)
                ps.setTimestamp(5, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun query(serverId: String, from: Instant, to: Instant, limit: Int = 1000): List<Map<String, Any?>> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT cpu_percent, ram_used_mb, ram_total_mb, recorded_at FROM agent_metrics " +
                    "WHERE server_id = ? AND recorded_at >= ? AND recorded_at <= ? " +
                    "ORDER BY recorded_at ASC LIMIT ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setTimestamp(2, Timestamp.from(from))
                ps.setTimestamp(3, Timestamp.from(to))
                ps.setInt(4, limit.coerceIn(1, 5000))
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "cpuPercent" to rs.getFloat("cpu_percent"),
                                "ramUsedMb" to rs.getFloat("ram_used_mb"),
                                "ramTotalMb" to rs.getFloat("ram_total_mb"),
                                "recordedAt" to rs.getTimestamp("recorded_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun deleteOlderThan(days: Int) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM agent_metrics WHERE recorded_at < NOW() - INTERVAL '1 day' * ?",
            ).use { ps ->
                ps.setInt(1, days)
                ps.executeUpdate()
            }
        }
    }
}
