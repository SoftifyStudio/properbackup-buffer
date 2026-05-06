package pl.danielniemiec.properbackup.buffer.pathsidx

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class BackupRootStore(private val db: Database) {

    data class Row(val pathId: String, val rootPath: String, val updatedAt: String)

    fun upsertBatch(machineId: String, roots: List<Pair<String, String>>) {
        if (roots.isEmpty()) return
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO machine_backup_root(machine_id, path_id, root_path, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (machine_id, path_id) DO UPDATE SET root_path = EXCLUDED.root_path, " +
                    "updated_at = EXCLUDED.updated_at",
            ).use { ps ->
                val now = Timestamp.from(Instant.now())
                for ((pathId, rootPath) in roots) {
                    ps.setString(1, machineId)
                    ps.setString(2, pathId)
                    ps.setString(3, rootPath)
                    ps.setTimestamp(4, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun listForMachine(machineId: String): List<Row> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT path_id, root_path, updated_at FROM machine_backup_root WHERE machine_id = ? " +
                    "ORDER BY root_path ASC",
            ).use { ps ->
                ps.setString(1, machineId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Row>()
                    while (rs.next()) {
                        out.add(
                            Row(
                                pathId = rs.getString("path_id"),
                                rootPath = rs.getString("root_path"),
                                updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }
}
