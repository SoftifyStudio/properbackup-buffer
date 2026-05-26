package pl.danielniemiec.properbackup.buffer.recovery

import org.testcontainers.containers.PostgreSQLContainer
import pl.danielniemiec.properbackup.buffer.db.Database

object TestDatabaseHelper {

    private val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("properbackup_test")
        .withUsername("test")
        .withPassword("test")

    private lateinit var sharedDb: Database
    private var initialized = false

    init {
        pg.start()
    }

    @Synchronized
    fun createDatabase(): Database {
        if (!initialized) {
            sharedDb = Database(pg.jdbcUrl, pg.username, pg.password)
            initialized = true
        }
        return sharedDb
    }

    fun seedUserAndServer(db: Database, userId: String = "user-1", serverId: String = "server-1"): Pair<String, String> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "$userId@test.com")
                ps.setString(3, "\$2a\$10\$dummyhashfortest000000000000000000000000000000000")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "Test Server")
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
        return userId to serverId
    }

    fun seedSnapshot(db: Database, serverId: String, pathId: String = "abcd1234", objectName: String = "test-obj-1"): Long {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT id FROM archive_snapshot WHERE server_id = ? AND object_name = ?""",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, objectName)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getLong("id")
                }
            }
            conn.prepareStatement(
                """INSERT INTO archive_snapshot (server_id, path_id, object_name, flag, size_bytes)
                   VALUES (?, ?, ?, 'A', 1024) RETURNING id""",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, pathId)
                ps.setString(3, objectName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong("id")
                }
            }
        }
    }

    fun cleanRecoveryTables(db: Database) {
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM recovery_audit_log")
                stmt.execute("DELETE FROM recovery_dry_run")
                stmt.execute("DELETE FROM recovery_operation")
                stmt.execute("UPDATE archive_snapshot SET pre_recovery_session_id = NULL")
                stmt.execute("DELETE FROM recovery_session")
            }
        }
    }
}
