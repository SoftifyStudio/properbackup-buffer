package pl.danielniemiec.properbackup.buffer.pathsidx

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant
import java.util.LinkedHashMap

class ArchiveSnapshotStore(private val db: Database) {

    fun insert(
        serverId: String,
        pathId: String,
        objectName: String,
        flag: String,
        sizeBytes: Long,
        sealedAt: Instant,
    ) {
        val f = if (flag.isNotEmpty()) flag[0].toString() else "A"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO archive_snapshot(server_id, path_id, object_name, flag, size_bytes, sealed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (server_id, object_name) DO NOTHING",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, pathId)
                ps.setString(3, objectName)
                ps.setString(4, f)
                ps.setLong(5, sizeBytes)
                ps.setTimestamp(6, Timestamp.from(sealedAt))
                ps.executeUpdate()
            }
        }
    }

    fun listObjectNames(serverId: String): List<String> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT object_name FROM archive_snapshot WHERE server_id = ? ORDER BY sealed_at",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out.add(rs.getString("object_name"))
                    return out
                }
            }
        }
    }

    fun countsByPathId(serverId: String): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT path_id, COUNT(*)::BIGINT AS c FROM archive_snapshot WHERE server_id = ? GROUP BY path_id",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        m[rs.getString("path_id")] = rs.getLong("c")
                    }
                }
            }
        }
        return m
    }

    fun listRecent(serverId: String, pathId: String?, limit: Int): List<Map<String, Any?>> {
        val lim = limit.coerceIn(1, 5000)
        val sql = StringBuilder(
            "SELECT id, path_id, object_name, flag, size_bytes, sealed_at FROM archive_snapshot WHERE server_id = ? ",
        )
        if (!pathId.isNullOrBlank()) sql.append("AND path_id = ? ")
        sql.append("ORDER BY sealed_at DESC LIMIT ?")
        db.getConnection().use { conn ->
            conn.prepareStatement(sql.toString()).use { ps ->
                ps.setString(1, serverId)
                var i = 2
                if (!pathId.isNullOrBlank()) ps.setString(i++, pathId)
                ps.setInt(i, lim)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Map<String, Any?>>()
                    while (rs.next()) {
                        out.add(
                            linkedMapOf(
                                "id" to rs.getLong("id"),
                                "pathId" to rs.getString("path_id"),
                                "objectName" to rs.getString("object_name"),
                                "flag" to rs.getString("flag"),
                                "sizeBytes" to rs.getLong("size_bytes"),
                                "sealedAt" to rs.getTimestamp("sealed_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun latestSealedAt(serverId: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT MAX(sealed_at) AS latest FROM archive_snapshot WHERE server_id = ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getTimestamp("latest")?.toInstant()?.toString() else null
                }
            }
        }
    }

    fun latestSealedAtForPath(serverId: String, pathId: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT MAX(sealed_at) AS latest FROM archive_snapshot WHERE server_id = ? AND path_id = ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, pathId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getTimestamp("latest")?.toInstant()?.toString() else null
                }
            }
        }
    }

    fun deleteOlderThan(days: Int): Int {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM archive_snapshot WHERE sealed_at < NOW() - (?::text || ' days')::interval",
            ).use { ps ->
                ps.setInt(1, days)
                return ps.executeUpdate()
            }
        }
    }
}
