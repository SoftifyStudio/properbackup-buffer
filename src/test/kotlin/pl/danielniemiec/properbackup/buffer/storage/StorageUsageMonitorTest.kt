package pl.danielniemiec.properbackup.buffer.storage

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import pl.danielniemiec.properbackup.buffer.db.Database

class StorageUsageMonitorTest {

    companion object {
        private val pg = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("properbackup_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database

        @JvmStatic
        @BeforeAll
        fun startDb() {
            pg.start()
            db = Database(pg.jdbcUrl, pg.username, pg.password)
        }

        @JvmStatic
        @AfterAll
        fun stopDb() {
            pg.stop()
        }
    }

    @BeforeEach
    fun seedData() {
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM storage_spike_alert")
                stmt.execute("DELETE FROM user_storage_daily")
            }
        }
        seedUserAndServer("user-mon-1", "server-mon-1")
    }

    private fun seedUserAndServer(userId: String, serverId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "$userId@test.com")
                ps.setString(3, "\$2a\$10\$dummyhashfortest000000000000000000000000000000000")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "Test Server")
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `recordDailyUsage inserts row in user_storage_daily`() {
        val monitor = StorageUsageMonitor(db)
        monitor.recordDailyUsage()

        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_storage_daily WHERE user_id = ?").use { ps ->
                ps.setString(1, "user-mon-1")
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertTrue(rs.getInt(1) >= 1)
                }
            }
        }
    }

    @Test
    fun `isUserSoftBlocked returns false when no spike`() {
        val monitor = StorageUsageMonitor(db)
        assertFalse(monitor.isUserSoftBlocked("user-mon-1"))
    }

    @Test
    fun `isUserSoftBlocked returns true after spike alert`() {
        val monitor = StorageUsageMonitor(db)

        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO storage_spike_alert (user_id, bytes_in_window, window_start, window_end)
                   VALUES (?, ?, NOW() - INTERVAL '30 minutes', NOW())"""
            ).use { ps ->
                ps.setString(1, "user-mon-1")
                ps.setLong(2, 60L * 1024 * 1024 * 1024) // 60 GB
                ps.executeUpdate()
            }
        }

        assertTrue(monitor.isUserSoftBlocked("user-mon-1"))
    }
}
