package pl.danielniemiec.properbackup.buffer.payment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import java.util.UUID

@Testcontainers
class SubscriptionAuditLogTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("properbackup_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var db: Database
    private lateinit var auditLog: SubscriptionAuditLog

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        auditLog = SubscriptionAuditLog(db)
    }

    @Test
    fun `append inserts audit entry`() {
        val userId = createTestUser()
        auditLog.append(userId, "checkout_completed", "plan=monthly")

        val entries = auditLog.listByUser(userId)
        assertEquals(1, entries.size)
        assertEquals("checkout_completed", entries[0].action)
        assertEquals("plan=monthly", entries[0].detail)
        assertEquals(userId, entries[0].userId)
    }

    @Test
    fun `multiple entries are ordered by created_at DESC`() {
        val userId = createTestUser()
        auditLog.append(userId, "registration_no_plan")
        auditLog.append(userId, "checkout_completed")
        auditLog.append(userId, "cancel_at_period_end")

        val entries = auditLog.listByUser(userId)
        assertEquals(3, entries.size)
        // Most recent first
        assertEquals("cancel_at_period_end", entries[0].action)
        assertEquals("checkout_completed", entries[1].action)
        assertEquals("registration_no_plan", entries[2].action)
    }

    @Test
    fun `countByAction returns correct count`() {
        val userId = createTestUser()
        auditLog.append(userId, "checkout_completed")
        auditLog.append(userId, "checkout_completed")
        auditLog.append(userId, "cancel_at_period_end")

        assertEquals(2, auditLog.countByAction(userId, "checkout_completed"))
        assertEquals(1, auditLog.countByAction(userId, "cancel_at_period_end"))
        assertEquals(0, auditLog.countByAction(userId, "reactivate"))
    }

    @Test
    fun `listByUser respects limit`() {
        val userId = createTestUser()
        repeat(10) { auditLog.append(userId, "action_$it") }

        val limited = auditLog.listByUser(userId, limit = 5)
        assertEquals(5, limited.size)
    }

    @Test
    fun `entries are isolated per user`() {
        val userA = createTestUser()
        val userB = createTestUser()
        auditLog.append(userA, "user_a_action")
        auditLog.append(userB, "user_b_action")

        val aEntries = auditLog.listByUser(userA)
        assertEquals(1, aEntries.size)
        assertEquals("user_a_action", aEntries[0].action)
        assertTrue(aEntries.none { it.userId == userB })
    }

    private fun createTestUser(): String {
        val userId = UUID.randomUUID().toString()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, "$userId@test.com")
                stmt.setString(3, "\$2a\$10\$test_hash_placeholder_xxxxxx")
                stmt.executeUpdate()
            }
        }
        return userId
    }
}
