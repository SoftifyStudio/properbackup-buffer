package pl.danielniemiec.properbackup.buffer.payment

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.util.UUID

/**
 * Verifies that Stripe resources (price IDs, subscription IDs) are kept on
 * separate columns for test vs live mode. Both modes must coexist without
 * stepping on each other's records — flipping a user's `stripe_test_mode`
 * flag must not corrupt or hide the previous mode's data.
 *
 * Backstops the two P1 bugs identified in the 2026-05-24 audit:
 *   1. `stripe_price_config` PK was only `plan_key` → live priceId overwrote test.
 *   2. `users.stripe_subscription_id` had no live counterpart → flipping the flag
 *      silently overwrote the test sub id with a live one.
 */
@Testcontainers
class StripePerModeIsolationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("permode_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database
        private lateinit var userStore: UserStore

        @BeforeAll
        @JvmStatic
        fun setup() {
            db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
            userStore = UserStore(db)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            db.close()
        }
    }

    private lateinit var userId: String

    @BeforeEach
    fun ensureUser() {
        userId = UUID.randomUUID().toString()
        val email = "permode-${userId.take(8)}@properbackup.dev"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO users (id, email, bcrypt_hash, created_at, storage_limit_bytes)
                   VALUES (?, ?, '${'$'}2a${'$'}10${'$'}dummy', NOW(), 2199023255552)""",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, email)
                ps.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------------
    // stripe_price_config — PK (plan_key, mode)
    // ------------------------------------------------------------------

    @Test
    fun `test and live prices coexist for the same plan_key`() {
        insertPrice("monthly", "test", "price_test_AAA")
        insertPrice("monthly", "live", "price_live_BBB")

        assertEquals("price_test_AAA", selectPrice("monthly", "test"))
        assertEquals("price_live_BBB", selectPrice("monthly", "live"))
    }

    @Test
    fun `inserting live price does not overwrite test price for same plan_key`() {
        insertPrice("annual", "test", "price_test_old")
        insertPrice("annual", "live", "price_live_new")

        // Test row must still be intact — this is the regression we are guarding.
        assertEquals("price_test_old", selectPrice("annual", "test"))
        assertEquals("price_live_new", selectPrice("annual", "live"))
    }

    @Test
    fun `upserting the same mode updates only that mode's row`() {
        insertPrice("monthly", "test", "price_test_v1")
        insertPrice("monthly", "live", "price_live_v1")

        // Re-upsert test → live row must remain untouched.
        insertPrice("monthly", "test", "price_test_v2")

        assertEquals("price_test_v2", selectPrice("monthly", "test"))
        assertEquals("price_live_v1", selectPrice("monthly", "live"))
    }

    // ------------------------------------------------------------------
    // users.stripe_subscription_id  vs  stripe_live_subscription_id
    // ------------------------------------------------------------------

    @Test
    fun `subscription id is written to the column matching the user's current mode`() {
        // User defaults to test mode (DB default).
        userStore.updateStripeSubscriptionId(userId, "sub_test_abc")

        val user = userStore.findById(userId).orElseThrow()
        assertEquals("sub_test_abc", user.stripeSubscriptionId, "test column populated")
        assertNull(user.stripeLiveSubscriptionId, "live column untouched")
        assertEquals("sub_test_abc", user.currentStripeSubscriptionId())
    }

    @Test
    fun `flipping to live preserves test sub id and writes new live sub id separately`() {
        // 1. Active test subscription.
        userStore.updateStripeSubscriptionId(userId, "sub_test_ORIGINAL")

        // 2. Operator flips the user to live (e.g. via SQL migration script).
        setTestMode(userId, false)

        // 3. New live checkout writes a new sub id.
        userStore.updateStripeSubscriptionId(userId, "sub_live_NEW")

        val user = userStore.findById(userId).orElseThrow()
        // Regression: pre-fix the test column would have been overwritten with sub_live_NEW.
        assertEquals("sub_test_ORIGINAL", user.stripeSubscriptionId, "test sub id preserved")
        assertEquals("sub_live_NEW", user.stripeLiveSubscriptionId, "live sub id stored separately")
        assertFalse(user.stripeTestMode)
        assertEquals("sub_live_NEW", user.currentStripeSubscriptionId(), "current returns live")
    }

    @Test
    fun `findByStripeSubscriptionId resolves both test and live ids`() {
        userStore.updateStripeSubscriptionId(userId, "sub_test_FOO")
        setTestMode(userId, false)
        userStore.updateStripeSubscriptionId(userId, "sub_live_BAR")

        val byTest = userStore.findByStripeSubscriptionId("sub_test_FOO")
        val byLive = userStore.findByStripeSubscriptionId("sub_live_BAR")

        assertTrue(byTest.isPresent, "lookup by test sub id should find the user")
        assertTrue(byLive.isPresent, "lookup by live sub id should find the user")
        assertEquals(userId, byTest.get().id)
        assertEquals(userId, byLive.get().id)
    }

    @Test
    fun `deactivateSubscription clears only the column matching current mode`() {
        userStore.updateStripeSubscriptionId(userId, "sub_test_KEEP")
        setTestMode(userId, false)
        userStore.updateStripeSubscriptionId(userId, "sub_live_CLEAR")

        // While in live mode, deactivate.
        userStore.deactivateSubscription(userId)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals("sub_test_KEEP", user.stripeSubscriptionId, "test sub id must survive live deactivation")
        assertNull(user.stripeLiveSubscriptionId, "live sub id cleared")
        assertNull(user.subscriptionPlan)
    }

    @Test
    fun `findByStripeCustomerId resolves both test and live customer ids`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET stripe_customer_id = 'cus_test_X', stripe_live_customer_id = 'cus_live_Y' WHERE id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }

        assertTrue(userStore.findByStripeCustomerId("cus_test_X").isPresent)
        assertTrue(userStore.findByStripeCustomerId("cus_live_Y").isPresent)
        assertEquals(userId, userStore.findByStripeCustomerId("cus_live_Y").get().id)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun insertPrice(planKey: String, mode: String, priceId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO stripe_price_config (plan_key, mode, stripe_price_id) VALUES (?, ?, ?)
                   ON CONFLICT (plan_key, mode) DO UPDATE SET stripe_price_id = EXCLUDED.stripe_price_id, updated_at = NOW()""",
            ).use { ps ->
                ps.setString(1, planKey)
                ps.setString(2, mode)
                ps.setString(3, priceId)
                ps.executeUpdate()
            }
        }
    }

    private fun selectPrice(planKey: String, mode: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ? AND mode = ?",
            ).use { ps ->
                ps.setString(1, planKey)
                ps.setString(2, mode)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("stripe_price_id") else null
                }
            }
        }
    }

    private fun setTestMode(userId: String, testMode: Boolean) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET stripe_test_mode = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, testMode)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }
}
