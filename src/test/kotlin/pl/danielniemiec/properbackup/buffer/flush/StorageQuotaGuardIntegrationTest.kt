package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

@Testcontainers
class StorageQuotaGuardIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("properbackup_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database
        private lateinit var userStore: UserStore

        @BeforeAll
        @JvmStatic
        fun setupDb() {
            db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
            userStore = UserStore(db)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            db.close()
        }
    }

    @BeforeEach
    fun cleanTables() {
        db.getConnection().use { conn ->
            conn.createStatement().execute("DELETE FROM archive_snapshot")
            conn.createStatement().execute("DELETE FROM servers")
            conn.createStatement().execute("DELETE FROM users")
        }
    }

    private fun createUser(userId: String, storageLimitBytes: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash, created_at, storage_limit_bytes) VALUES (?, ?, 'hash', NOW(), ?)",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "$userId@test.com")
                ps.setLong(3, storageLimitBytes)
                ps.executeUpdate()
            }
        }
    }

    private fun createServer(serverId: String, userId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, 'test-server', ?)",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    private var snapshotSeq = 0

    private fun addArchiveSnapshot(serverId: String, sizeBytes: Long) {
        snapshotSeq++
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO archive_snapshot (server_id, path_id, object_name, flag, size_bytes, sealed_at) VALUES (?, 'P1', ?, 'A', ?, NOW())",
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "obj_${serverId}_$snapshotSeq")
                ps.setLong(3, sizeBytes)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `canUpload returns true when under quota`() {
        createUser("U1", 1_000_000)
        createServer("S1", "U1")
        addArchiveSnapshot("S1", 500_000)

        val guard = StorageQuotaGuard(db, userStore)
        assertTrue(guard.canUpload("U1", 100_000))
    }

    @Test
    fun `canUpload returns false when quota would be exceeded`() {
        createUser("U1", 1_000_000)
        createServer("S1", "U1")
        addArchiveSnapshot("S1", 900_000)

        val guard = StorageQuotaGuard(db, userStore)
        assertFalse(guard.canUpload("U1", 200_000))
    }

    @Test
    fun `canUpload returns false at exact boundary`() {
        createUser("U1", 1_000_000)
        createServer("S1", "U1")
        addArchiveSnapshot("S1", 1_000_000)

        val guard = StorageQuotaGuard(db, userStore)
        assertFalse(guard.canUpload("U1", 1))
    }

    @Test
    fun `checkQuota shows correct used and remaining bytes`() {
        createUser("U1", 1_000_000)
        createServer("S1", "U1")
        addArchiveSnapshot("S1", 300_000)
        addArchiveSnapshot("S1", 200_000)

        val guard = StorageQuotaGuard(db, userStore)
        val status = guard.checkQuota("U1")
        assertTrue(status.usedBytes == 500_000L)
        assertTrue(status.limitBytes == 1_000_000L)
        assertTrue(status.remainingBytes == 500_000L)
        assertTrue(status.usedPercent == 50.0)
        assertFalse(status.exceeded)
    }

    @Test
    fun `checkQuota for unknown user returns exceeded`() {
        val guard = StorageQuotaGuard(db, userStore)
        val status = guard.checkQuota("NONEXISTENT")
        assertTrue(status.exceeded)
    }

    @Test
    fun `multiple servers aggregate storage per user`() {
        createUser("U1", 1_000_000)
        createServer("S1", "U1")
        createServer("S2", "U1")
        addArchiveSnapshot("S1", 400_000)
        addArchiveSnapshot("S2", 500_000)

        val guard = StorageQuotaGuard(db, userStore)
        val status = guard.checkQuota("U1")
        assertTrue(status.usedBytes == 900_000L)
        assertTrue(guard.canUpload("U1", 100_000))
        assertFalse(guard.canUpload("U1", 200_000))
    }

    @Test
    fun `canUpload allows zero additional bytes when under quota`() {
        createUser("U1", 1_000_000)

        val guard = StorageQuotaGuard(db, userStore)
        assertTrue(guard.canUpload("U1", 0))
    }
}
