package pl.danielniemiec.properbackup.buffer.pathsidx

import pl.danielniemiec.properbackup.buffer.db.Database

class BackupConfigStore(private val db: Database) {

    data class ConfiguredPath(val folderPath: String, val enabled: Boolean, val createdAt: String)

    fun listForServer(serverId: String): List<ConfiguredPath> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT folder_path, enabled, created_at FROM server_backup_config WHERE server_id = ? ORDER BY folder_path ASC",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<ConfiguredPath>()
                    while (rs.next()) {
                        out.add(
                            ConfiguredPath(
                                folderPath = rs.getString("folder_path"),
                                enabled = rs.getBoolean("enabled"),
                                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                            ),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun addPath(serverId: String, folderPath: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO server_backup_config (server_id, folder_path) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, folderPath)
                ps.executeUpdate()
            }
        }
    }

    fun removePath(serverId: String, folderPath: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM server_backup_config WHERE server_id = ? AND folder_path = ?",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, folderPath)
                ps.executeUpdate()
            }
        }
    }

    fun setEnabled(serverId: String, folderPath: String, enabled: Boolean) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE server_backup_config SET enabled = ? WHERE server_id = ? AND folder_path = ?",
            ).use { ps ->
                ps.setBoolean(1, enabled)
                ps.setString(2, serverId)
                ps.setString(3, folderPath)
                ps.executeUpdate()
            }
        }
    }
}
