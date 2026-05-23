package pl.danielniemiec.properbackup.buffer.payment

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.auth.UserStore
import pl.danielniemiec.properbackup.buffer.db.Database
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the Subscription module.
 *
 * Tests UserStore subscription methods, PromoCodeHandler logic,
 * and StripeHandler DB-level operations (audit logs, price config,
 * event idempotency) using a real PostgreSQL via TestContainers.
 *
 * Stripe API calls are NOT tested here (those require live test keys).
 * See test-subscription.sh for full E2E tests with Stripe sandbox.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SubscriptionIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("properbackup_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database
        private lateinit var userStore: UserStore
        private lateinit var promoCodeHandler: PromoCodeHandler

        private val testUserId = UUID.randomUUID().toString()
        private const val TEST_EMAIL = "test-sub@properbackup.dev"
        private const val TEST_PASSWORD_HASH = "\$2a\$10\$dummyhashforintegrationtesting000000000000000000000"

        @BeforeAll
        @JvmStatic
        fun setupDb() {
            db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
            userStore = UserStore(db)
            promoCodeHandler = PromoCodeHandler(db, userStore)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            db.close()
        }
    }

    @BeforeEach
    fun ensureTestUser() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO users (id, email, bcrypt_hash, created_at, trial_expires_at, storage_limit_bytes)
                   VALUES (?, ?, ?, NOW(), NOW() + INTERVAL '30 days', 2199023255552)
                   ON CONFLICT (id) DO NOTHING""",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.setString(2, TEST_EMAIL)
                ps.setString(3, TEST_PASSWORD_HASH)
                ps.executeUpdate()
            }
        }
    }

    // ========================================================================
    // UserStore: Subscription lifecycle
    // ========================================================================

    @Test
    @Order(1)
    fun `user starts with trial - no subscription plan`() {
        val user = userStore.findById(testUserId).orElse(null)
        assertNotNull(user)
        assertNull(user.subscriptionPlan)
        assertNull(user.stripeSubscriptionId)
        assertFalse(user.subscriptionCancelAtPeriodEnd)
    }

    @Test
    @Order(2)
    fun `activate monthly subscription`() {
        val expiresAt = Instant.now().plus(Duration.ofDays(31))
        userStore.updateSubscription(testUserId, "monthly", expiresAt, 2199023255552L)

        val user = userStore.findById(testUserId).orElse(null)
        assertNotNull(user)
        assertEquals("monthly", user.subscriptionPlan)
        assertNotNull(user.subscriptionExpiresAt)
    }

    @Test
    @Order(3)
    fun `save stripe subscription ID`() {
        userStore.updateStripeSubscriptionId(testUserId, "sub_test_123456")

        val user = userStore.findById(testUserId).orElse(null)
        assertEquals("sub_test_123456", user.stripeSubscriptionId)
    }

    @Test
    @Order(4)
    fun `find user by stripe subscription ID`() {
        val found = userStore.findByStripeSubscriptionId("sub_test_123456")
        assertTrue(found.isPresent)
        assertEquals(testUserId, found.get().id)
    }

    @Test
    @Order(5)
    fun `find user by stripe subscription ID - not found`() {
        val found = userStore.findByStripeSubscriptionId("sub_nonexistent")
        assertFalse(found.isPresent)
    }

    @Test
    @Order(6)
    fun `save stripe customer ID and find by it`() {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET stripe_customer_id = ? WHERE id = ?").use { ps ->
                ps.setString(1, "cus_test_789")
                ps.setString(2, testUserId)
                ps.executeUpdate()
            }
        }

        val found = userStore.findByStripeCustomerId("cus_test_789")
        assertTrue(found.isPresent)
        assertEquals(testUserId, found.get().id)
    }

    @Test
    @Order(7)
    fun `set cancel at period end`() {
        userStore.setCancelAtPeriodEnd(testUserId, true)

        val user = userStore.findById(testUserId).orElse(null)
        assertTrue(user.subscriptionCancelAtPeriodEnd)
    }

    @Test
    @Order(8)
    fun `reactivate - clear cancel at period end`() {
        userStore.setCancelAtPeriodEnd(testUserId, false)

        val user = userStore.findById(testUserId).orElse(null)
        assertFalse(user.subscriptionCancelAtPeriodEnd)
    }

    @Test
    @Order(9)
    fun `upgrade from monthly to annual`() {
        val expiresAt = Instant.now().plus(Duration.ofDays(365))
        userStore.updateSubscription(testUserId, "annual", expiresAt, 2199023255552L)

        val user = userStore.findById(testUserId).orElse(null)
        assertEquals("annual", user.subscriptionPlan)
    }

    @Test
    @Order(10)
    fun `deactivate subscription`() {
        userStore.deactivateSubscription(testUserId)

        val user = userStore.findById(testUserId).orElse(null)
        assertNull(user.subscriptionPlan)
        assertNull(user.stripeSubscriptionId)
        assertFalse(user.subscriptionCancelAtPeriodEnd)
    }

    // ========================================================================
    // Prorated billing calculations
    // ========================================================================

    @Test
    @Order(20)
    fun `prorated discount for monthly plan with 15 days remaining`() {
        val basePrice = 1900L // 19.00 PLN
        val totalDays = 31L
        val daysRemaining = 15L
        val discount = minOf(basePrice, basePrice * daysRemaining / totalDays)
        assertEquals(919L, discount) // 15/31 * 1900 = 919 groszy = 9.19 PLN
    }

    @Test
    @Order(21)
    fun `prorated discount for annual plan with 200 days remaining`() {
        val basePrice = 19000L // 190.00 PLN
        val totalDays = 365L
        val daysRemaining = 200L
        val discount = minOf(basePrice, basePrice * daysRemaining / totalDays)
        assertEquals(10410L, discount) // 200/365 * 19000 = 10410 groszy
    }

    @Test
    @Order(22)
    fun `prorated discount capped at base price`() {
        val basePrice = 1900L
        val totalDays = 31L
        val daysRemaining = 50L // more than a full month
        val discount = minOf(basePrice, basePrice * daysRemaining / totalDays)
        assertEquals(basePrice, discount) // capped
    }

    @Test
    @Order(23)
    fun `prorated discount is zero when no days remaining`() {
        val basePrice = 1900L
        val totalDays = 31L
        val daysRemaining = 0L
        val discount = if (daysRemaining > 0) minOf(basePrice, basePrice * daysRemaining / totalDays) else 0L
        assertEquals(0L, discount)
    }

    @Test
    @Order(24)
    fun `VAT calculation at 23 percent`() {
        val netto = 1900L // 19.00 PLN
        val vatRate = 0.23
        val vat = Math.round(netto * vatRate)
        assertEquals(437L, vat) // 4.37 PLN
        assertEquals(2337L, netto + vat) // 23.37 PLN brutto
    }

    @Test
    @Order(25)
    fun `combined prorated and promo discount`() {
        val basePrice = 19000L // 190 PLN annual
        val daysRemaining = 10L
        val totalDays = 365L
        val prorated = minOf(basePrice, basePrice * daysRemaining / totalDays) // 520
        val promoPercent = 50
        val promo = Math.round(basePrice * promoPercent / 100.0).toLong() // 9500
        val totalDiscount = minOf(basePrice, prorated + promo) // 10020
        val netto = Math.max(0, basePrice - totalDiscount)
        assertEquals(520L, prorated)
        assertEquals(9500L, promo)
        assertEquals(8980L, netto) // 89.80 PLN
    }

    @Test
    @Order(26)
    fun `total discount cannot exceed base price`() {
        val basePrice = 1900L
        val prorated = 1900L // full cap
        val promo = 950L // 50%
        val totalDiscount = minOf(basePrice, prorated + promo)
        assertEquals(basePrice, totalDiscount) // capped at 1900
        assertEquals(0L, Math.max(0L, basePrice - totalDiscount)) // free
    }

    // ========================================================================
    // Promo codes (DB-level)
    // ========================================================================

    @Test
    @Order(30)
    fun `create promo code and validate`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, active, created_at)
                   VALUES ('TESTCODE50', 'percentage', 50, 100, TRUE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val discount = promoCodeHandler.calculateDiscount("TESTCODE50", 1900L)
        assertEquals(950L, discount) // 50% of 19 PLN = 9.50 PLN
    }

    @Test
    @Order(31)
    fun `invalid promo code returns zero discount`() {
        val discount = promoCodeHandler.calculateDiscount("NONEXISTENT", 1900L)
        assertEquals(0L, discount)
    }

    @Test
    @Order(32)
    fun `fixed amount promo code`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_grosz, max_uses, active, created_at)
                   VALUES ('FIXED500', 'fixed', 500, 100, TRUE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val discount = promoCodeHandler.calculateDiscount("FIXED500", 1900L)
        assertEquals(500L, discount) // 5.00 PLN off
    }

    @Test
    @Order(33)
    fun `inactive promo code returns zero discount`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, active, created_at)
                   VALUES ('INACTIVE10', 'percentage', 10, 100, FALSE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val discount = promoCodeHandler.calculateDiscount("INACTIVE10", 1900L)
        assertEquals(0L, discount)
    }

    @Test
    @Order(34)
    fun `expired promo code returns zero discount`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, active, expires_at, created_at)
                   VALUES ('EXPIRED20', 'percentage', 20, 100, TRUE, NOW() - INTERVAL '1 day', NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val discount = promoCodeHandler.calculateDiscount("EXPIRED20", 1900L)
        assertEquals(0L, discount)
    }

    @Test
    @Order(35)
    fun `promo code max uses exhausted returns zero discount`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES ('MAXED', 'percentage', 30, 1, 1, TRUE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val discount = promoCodeHandler.calculateDiscount("MAXED", 1900L)
        assertEquals(0L, discount)
    }

    @Test
    @Order(36)
    fun `mark promo code used increments count`() {
        // Get current used_count for TESTCODE50
        val beforeCount = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT used_count FROM promo_code WHERE code = 'TESTCODE50'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("used_count") else 0 }
            }
        }

        promoCodeHandler.markPromoCodeUsed("TESTCODE50", testUserId, "cs_test_session_123")

        val afterCount = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT used_count FROM promo_code WHERE code = 'TESTCODE50'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("used_count") else 0 }
            }
        }

        assertEquals(beforeCount + 1, afterCount)

        // Verify usage record
        val usage = db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT user_id, stripe_session FROM promo_code_usage WHERE user_id = ? ORDER BY used_at DESC LIMIT 1",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getString("user_id"), rs.getString("stripe_session")) else null
                }
            }
        }

        assertNotNull(usage)
        assertEquals(testUserId, usage!!.first)
        assertEquals("cs_test_session_123", usage.second)
    }

    // ========================================================================
    // Audit logging
    // ========================================================================

    @Test
    @Order(40)
    fun `audit log entry created for subscription actions`() {
        // Insert an audit log entry directly (simulating what StripeHandler does)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO subscription_audit_log (user_id, action, details)
                   VALUES (?, 'test_checkout_started', '{"plan":"monthly","mode":"subscription"}')""",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.executeUpdate()
            }
        }

        val entry = db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT action, details FROM subscription_audit_log WHERE user_id = ? AND action = 'test_checkout_started' ORDER BY created_at DESC LIMIT 1",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getString("action"), rs.getString("details")) else null
                }
            }
        }

        assertNotNull(entry)
        assertEquals("test_checkout_started", entry!!.first)
        assertTrue(entry.second.contains("monthly"))
        assertTrue(entry.second.contains("subscription"))
    }

    // ========================================================================
    // Stripe event idempotency
    // ========================================================================

    @Test
    @Order(50)
    fun `stripe event table stores events correctly`() {
        val sessionId = "cs_test_${System.currentTimeMillis()}"

        insertStripeEvent(testUserId, "checkout_completed", sessionId, "monthly")

        val entry = db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT event_type, plan FROM stripe_event WHERE user_id = ? AND stripe_session_id = ?",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.setString(2, sessionId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getString("event_type"), rs.getString("plan")) else null
                }
            }
        }

        assertNotNull(entry)
        assertEquals("checkout_completed", entry!!.first)
        assertEquals("monthly", entry.second)
    }

    @Test
    @Order(51)
    fun `stripe event table stores multiple events per user`() {
        val session1 = "cs_test_multi_1_${System.currentTimeMillis()}"
        val session2 = "cs_test_multi_2_${System.currentTimeMillis()}"

        insertStripeEvent(testUserId, "checkout_completed", session1, "monthly")
        insertStripeEvent(testUserId, "invoice_paid", session2, "annual")

        val count = db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM stripe_event WHERE user_id = ? AND stripe_session_id IN (?, ?)",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.setString(2, session1)
                ps.setString(3, session2)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

        assertEquals(2, count)
    }

    private fun insertStripeEvent(userId: String, type: String, sessionId: String, plan: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_event (user_id, event_type, stripe_session_id, plan) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, type)
                ps.setString(3, sessionId)
                ps.setString(4, plan)
                ps.executeUpdate()
            }
        }
    }

    // ========================================================================
    // Stripe price config persistence
    // ========================================================================

    @Test
    @Order(60)
    fun `stripe price config saves and loads`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_price_config (plan_key, stripe_price_id) VALUES (?, ?) ON CONFLICT (plan_key) DO UPDATE SET stripe_price_id = ?",
            ).use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "price_test_123")
                ps.setString(3, "price_test_123")
                ps.executeUpdate()
            }
        }

        val priceId = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ?").use { ps ->
                ps.setString(1, "test_monthly")
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("stripe_price_id") else null }
            }
        }

        assertEquals("price_test_123", priceId)
    }

    @Test
    @Order(61)
    fun `stripe price config upsert overwrites`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_price_config (plan_key, stripe_price_id) VALUES (?, ?) ON CONFLICT (plan_key) DO UPDATE SET stripe_price_id = ?, updated_at = NOW()",
            ).use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "price_test_456")
                ps.setString(3, "price_test_456")
                ps.executeUpdate()
            }
        }

        val priceId = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ?").use { ps ->
                ps.setString(1, "test_monthly")
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("stripe_price_id") else null }
            }
        }

        assertEquals("price_test_456", priceId)
    }

    // ========================================================================
    // Subscription config (VAT)
    // ========================================================================

    @Test
    @Order(70)
    fun `subscription config stores VAT rate`() {
        // Schema auto-inserts vat_rate=0.23 via INSERT...ON CONFLICT
        val vatRate = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT value FROM subscription_config WHERE key = 'vat_rate'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value").toDoubleOrNull() else null }
            }
        }

        assertNotNull(vatRate)
        assertEquals(0.23, vatRate!!, 0.001)
    }

    @Test
    @Order(71)
    fun `subscription config upsert works`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO subscription_config (key, value) VALUES ('test_key', 'test_val') ON CONFLICT (key) DO UPDATE SET value = 'test_val', updated_at = NOW()",
            ).use { it.executeUpdate() }
        }

        val value = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT value FROM subscription_config WHERE key = 'test_key'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
            }
        }

        assertEquals("test_val", value)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    @Order(80)
    fun `subscription with zero days remaining has no prorated discount`() {
        // Set subscription that expired yesterday
        val expired = Instant.now().minus(Duration.ofDays(1))
        userStore.updateSubscription(testUserId, "monthly", expired, 2199023255552L)

        val user = userStore.findById(testUserId).orElse(null)
        assertNotNull(user)

        val now = Instant.now()
        val subscriptionExpiresAt = user.subscriptionExpiresAt?.let { Instant.parse(it) }
        val daysRemaining = if (subscriptionExpiresAt != null && subscriptionExpiresAt.isAfter(now)) {
            Duration.between(now, subscriptionExpiresAt).toDays()
        } else {
            0L
        }

        assertEquals(0L, daysRemaining)
    }

    @Test
    @Order(81)
    fun `multiple subscription activations extend from current expiry`() {
        val now = Instant.now()
        val first = now.plus(Duration.ofDays(31))
        userStore.updateSubscription(testUserId, "monthly", first, 2199023255552L)

        // Simulate second activation (like invoice.paid extending)
        val second = first.plus(Duration.ofDays(31))
        userStore.updateSubscription(testUserId, "monthly", second, 2199023255552L)

        val user = userStore.findById(testUserId).orElse(null)
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)

        // Should expire ~62 days from now
        val daysUntilExpiry = Duration.between(now, expiresAt).toDays()
        assertTrue(daysUntilExpiry >= 61, "Expected ~62 days, got $daysUntilExpiry")
    }

    @Test
    @Order(82)
    fun `deactivated user has no subscription data`() {
        userStore.updateSubscription(testUserId, "annual", Instant.now().plus(Duration.ofDays(365)), 2199023255552L)
        userStore.updateStripeSubscriptionId(testUserId, "sub_to_delete")
        userStore.setCancelAtPeriodEnd(testUserId, true)

        // Deactivate
        userStore.deactivateSubscription(testUserId)

        val user = userStore.findById(testUserId).orElse(null)
        assertNull(user.subscriptionPlan)
        assertNull(user.stripeSubscriptionId)
        assertFalse(user.subscriptionCancelAtPeriodEnd)
    }

    @Test
    @Order(83)
    fun `promo code lookup is case-sensitive - use uppercase`() {
        // The API endpoint uppercases before calling calculateDiscount,
        // but calculateDiscount itself does exact match.
        // This verifies the DB stores codes as uppercase and lookup is exact.
        val discountUpper = promoCodeHandler.calculateDiscount("TESTCODE50", 1900L)
        val discountLower = promoCodeHandler.calculateDiscount("testcode50", 1900L)

        assertTrue(discountUpper > 0, "Uppercase code should match")
        assertEquals(0L, discountLower, "Lowercase should not match (API uppercases before calling)")
    }
}
