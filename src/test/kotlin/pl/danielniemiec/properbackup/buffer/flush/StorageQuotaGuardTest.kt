package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database

@Testcontainers
class StorageQuotaGuardTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("properbackup_test")
            withUsername("test")
            withPassword("test")
        }
    }

    @Test
    fun `TDD-G1c canUpload returns false (fail-closed) when DB unavailable`() {
        // Create a database with invalid connection to simulate DB down
        val brokenDb = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val userStore = UserStore(brokenDb)
        val guard = StorageQuotaGuard(brokenDb, userStore)

        // Close the DB pool to simulate unavailability
        brokenDb.close()

        // Must return false (fail-closed), NOT true (fail-open)
        val result = guard.canUpload("any-user-id", 1024)
        assertFalse(result, "StorageQuotaGuard MUST fail-closed when DB is unreachable")
    }

    @Test
    fun `canUpload returns true when user has quota remaining`() {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val userStore = UserStore(db)

        // Create a user with default 2TB limit
        val userId = java.util.UUID.randomUUID().toString()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, "$userId@test.com")
                stmt.setString(3, "\$2a\$10\$placeholder")
                stmt.executeUpdate()
            }
        }

        val guard = StorageQuotaGuard(db, userStore)
        assertTrue(guard.canUpload(userId, 1024), "Should allow upload when under quota")
        db.close()
    }

    @Test
    fun `canUpload returns false when user not found`() {
        val db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        val userStore = UserStore(db)
        val guard = StorageQuotaGuard(db, userStore)

        // Non-existent user should be blocked (fail-closed)
        assertFalse(guard.canUpload("nonexistent-user", 1024))
        db.close()
    }
}
