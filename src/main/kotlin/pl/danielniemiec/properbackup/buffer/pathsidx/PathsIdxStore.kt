package pl.danielniemiec.properbackup.buffer.pathsidx

import com.google.gson.GsonBuilder
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant
import java.util.LinkedHashMap

class PathsIdxStore(private val db: Database, private val serverId: String) {

    class PathsIdx(var version: Int, var updated: String, var paths: Map<String, String>)

    fun register(pathId: String, originalPath: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO paths_idx (path_id, server_id, original_path, registered_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (path_id, server_id) DO UPDATE SET " +
                    "original_path = EXCLUDED.original_path, " +
                    "registered_at = EXCLUDED.registered_at",
            ).use { ps ->
                ps.setString(1, pathId)
                ps.setString(2, serverId)
                ps.setString(3, originalPath)
                val now = Timestamp.from(Instant.now())
                ps.setTimestamp(4, now)
                val rows = ps.executeUpdate()
                if (rows > 0) println("[idx] upsert $pathId → $originalPath")
            }
        }
    }

    fun load(): PathsIdx {
        val paths = LinkedHashMap<String, String>()
        var updated = Instant.now().toString()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT path_id, original_path, MAX(registered_at) AS last_reg " +
                    "FROM paths_idx WHERE server_id = ? GROUP BY path_id, original_path ORDER BY path_id",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        paths[rs.getString("path_id")] = rs.getString("original_path")
                        updated = rs.getTimestamp("last_reg").toInstant().toString()
                    }
                }
            }
        }
        return PathsIdx(1, updated, paths)
    }

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(load())
}
