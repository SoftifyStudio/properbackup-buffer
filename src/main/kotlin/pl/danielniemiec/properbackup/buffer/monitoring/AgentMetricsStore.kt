package pl.danielniemiec.properbackup.buffer.monitoring

import com.google.gson.Gson
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class AgentMetricsStore(private val db: Database) {

    private val gson = Gson()

    fun insert(
        serverId: String,
        cpuPercent: Float,
        ramUsedMb: Float,
        ramTotalMb: Float,
        coreCount: Int = 1,
        perCoreCpu: List<Float>? = null,
    ) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO agent_metrics (server_id, cpu_percent, ram_used_mb, ram_total_mb, core_count, per_core_cpu, recorded_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setFloat(2, cpuPercent)
                ps.setFloat(3, ramUsedMb)
                ps.setFloat(4, ramTotalMb)
                ps.setInt(5, coreCount)
                ps.setString(6, if (perCoreCpu != null) gson.toJson(perCoreCpu) else null)
                ps.setTimestamp(7, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun query(serverId: String, from: Instant, to: Instant, limit: Int = 1000): List<Map<String, Any?>> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT cpu_percent, ram_used_mb, ram_total_mb, core_count, per_core_cpu, recorded_at FROM agent_metrics " +
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
                        val perCoreJson = rs.getString("per_core_cpu")
                        @Suppress("UNCHECKED_CAST")
                        val perCore: List<Float>? = if (perCoreJson != null) {
                            try {
                                (gson.fromJson(perCoreJson, List::class.java) as List<*>)
                                    .mapNotNull { (it as? Number)?.toFloat() }
                            } catch (_: Exception) { null }
                        } else null

                        out.add(
                            linkedMapOf(
                                "cpuPercent" to rs.getFloat("cpu_percent"),
                                "ramUsedMb" to rs.getFloat("ram_used_mb"),
                                "ramTotalMb" to rs.getFloat("ram_total_mb"),
                                "coreCount" to rs.getInt("core_count"),
                                "perCoreCpu" to perCore,
                                "recordedAt" to rs.getTimestamp("recorded_at").toInstant().toString(),
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
            conn.prepareStatement(
                "DELETE FROM agent_metrics WHERE recorded_at < NOW() - INTERVAL '1 day' * ?",
            ).use { ps ->
                ps.setInt(1, days)
                return ps.executeUpdate()
            }
        }
    }
}
