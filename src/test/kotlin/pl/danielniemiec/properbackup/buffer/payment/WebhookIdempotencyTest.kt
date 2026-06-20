package pl.danielniemiec.properbackup.buffer.payment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Instant
import java.util.UUID

@Testcontainers
class WebhookIdempotencyTest {

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
    private lateinit var idempotency: WebhookIdempotency

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        idempotency = WebhookIdempotency(db)
    }

    @Test
    fun `tryClaimStripeEventId returns true first time`() {
        val eventId = "evt_claim_${UUID.randomUUID()}"
        assertTrue(idempotency.tryClaimStripeEventId(eventId, "checkout.session.completed"))
    }

    @Test
    fun `tryClaimStripeEventId returns false on duplicate`() {
        val eventId = "evt_dup_${UUID.randomUUID()}"
        assertTrue(idempotency.tryClaimStripeEventId(eventId, "checkout.session.completed"))
        assertFalse(idempotency.tryClaimStripeEventId(eventId, "checkout.session.completed"))
        assertFalse(idempotency.tryClaimStripeEventId(eventId, "checkout.session.completed"))
    }

    @Test
    fun `isStaleEvent returns false when no prior event exists`() {
        val userId = createTestUser()
        assertFalse(idempotency.isStaleEvent(userId, Instant.now()))
    }

    @Test
    fun `isStaleEvent returns true when event is older than last processed`() {
        val userId = createTestUser()
        val future = Instant.now().plusSeconds(3600)
        idempotency.updateLastStripeEventAt(userId, future)

        // Event created before the last processed one → stale
        assertTrue(idempotency.isStaleEvent(userId, Instant.now()))
    }

    @Test
    fun `isStaleEvent returns false when event is newer than last processed`() {
        val userId = createTestUser()
        val past = Instant.now().minusSeconds(3600)
        idempotency.updateLastStripeEventAt(userId, past)

        // Event created after the last processed one → not stale
        assertFalse(idempotency.isStaleEvent(userId, Instant.now()))
    }

    @Test
    fun `updateLastStripeEventAt does not regress timestamp`() {
        val userId = createTestUser()
        val newer = Instant.now().plusSeconds(1000)
        val older = Instant.now().minusSeconds(1000)

        idempotency.updateLastStripeEventAt(userId, newer)
        idempotency.updateLastStripeEventAt(userId, older) // should NOT regress

        // Verify: event older than 'newer' should still be stale
        assertTrue(idempotency.isStaleEvent(userId, Instant.now()))
    }

    @Test
    fun `cleanupOldRecords removes entries beyond retention`() {
        val eventId = "evt_old_${UUID.randomUUID()}"
        idempotency.tryClaimStripeEventId(eventId, "test.event")

        // Manually set processed_at to 91 days ago
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE stripe_event_idempotency SET processed_at = NOW() - INTERVAL '91 days' WHERE stripe_event_id = ?"
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeUpdate()
            }
        }

        val deleted = idempotency.cleanupOldRecords(90)
        assertEquals(1, deleted)

        // Can claim again (record deleted)
        assertTrue(idempotency.tryClaimStripeEventId(eventId, "test.event"))
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
