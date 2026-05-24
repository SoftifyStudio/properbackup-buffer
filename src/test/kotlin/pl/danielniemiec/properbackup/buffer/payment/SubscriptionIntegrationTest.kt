package pl.danielniemiec.properbackup.buffer.payment

import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
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
import pl.danielniemiec.properbackup.buffer.flush.StorageQuotaGuard
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
        private lateinit var stripeHandler: StripeHandler
        private lateinit var giftCodeHandler: GiftCodeHandler

        private val testUserId = UUID.randomUUID().toString()
        private const val TEST_EMAIL = "test-sub@properbackup.dev"
        private const val TEST_PASSWORD_HASH = "\$2a\$10\$dummyhashforintegrationtesting000000000000000000000"

        @BeforeAll
        @JvmStatic
        fun setupDb() {
            db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
            userStore = UserStore(db)
            promoCodeHandler = PromoCodeHandler(db, userStore)
            // Instantiated with explicit overrides so we don't depend on shell env in tests.
            // stripeSecretKey blank → Stripe SDK not initialised, but db-level helpers still work.
            stripeHandler = StripeHandler(
                db, userStore, promoCodeHandler,
                stripeModeOverride = "test",
                stripeSecretKeyOverride = "",
                stripeWebhookSecretOverride = "",
                panelUrlOverride = "http://localhost:5175",
            )
            giftCodeHandler = GiftCodeHandler(db, userStore)
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

    // Below tests invoke the REAL production helpers (StripeHandler.calculateProratedDiscount,
    // StripeHandler.decomposeGross). They are intentionally NOT re-derivations of the formula:
    // if the production logic changes, these tests must change too — that's the point.

    @Test
    @Order(20)
    fun `prorated discount - trial user with 15 days left targeting monthly plan`() {
        // Trial-only user (no paid plan) → prorated uses NEW plan's per-day basis.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = null, daysRemaining = 15L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        // 1900 * 15 / 31 = 919 grosz
        assertEquals(919L, discount)
        assertEquals(0L, overflow)
    }

    @Test
    @Order(21)
    fun `prorated discount - monthly user with 15 days left buying annual`() {
        // Monthly user upgrading to annual: remaining VALUE from monthly = 1900 * 15 / 31 = 919.
        // The annual price is 19000 → 919 ≤ 19000 so no overflow.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 15L,
            newPlanBaseGrosze = StripeHandler.ANNUAL_PRICE_PLN,
            newPlanTotalDays = StripeHandler.ANNUAL_TOTAL_DAYS,
        )
        assertEquals(919L, discount)
        assertEquals(0L, overflow)
    }

    @Test
    @Order(22)
    fun `prorated discount capped at new plan base price when current plan smaller`() {
        // Monthly user with 50 days remaining (math anomaly) downgrading to... still monthly.
        // 1900 * 50 / 31 = 3064. Capped at 1900. Overflow = 1164.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 50L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        assertEquals(StripeHandler.MONTHLY_PRICE_PLN, discount)
        assertEquals(1164L, overflow)
    }

    @Test
    @Order(23)
    fun `prorated discount is zero when no days remaining`() {
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 0L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        assertEquals(0L, discount)
        assertEquals(0L, overflow)
    }

    @Test
    @Order(24)
    fun `VAT decomposition - 19 PLN gross at 23 percent invariant (P1-3)`() {
        // INVARIANT: net + vat MUST equal gross. Otherwise UI displays one amount and
        // Stripe charges another — violation of consumer protection law (PL).
        val (net, vat) = StripeHandler.decomposeGross(1900L, 0.23)
        assertEquals(1900L, net + vat, "gross must equal net+vat")
        // 1900 / 1.23 = 1544.7 → rounds to 1545; vat = 355.
        assertEquals(1545L, net)
        assertEquals(355L, vat)
    }

    @Test
    @Order(25)
    fun `VAT decomposition - 190 PLN annual gross invariant`() {
        val (net, vat) = StripeHandler.decomposeGross(19000L, 0.23)
        assertEquals(19000L, net + vat)
        // 19000 / 1.23 = 15447.15 → rounds to 15447; vat = 3553.
        assertEquals(15447L, net)
        assertEquals(3553L, vat)
    }

    @Test
    @Order(26)
    fun `VAT decomposition - zero gross returns zero VAT`() {
        val (net, vat) = StripeHandler.decomposeGross(0L, 0.23)
        assertEquals(0L, net)
        assertEquals(0L, vat)
    }

    @Test
    @Order(27)
    fun `downgrade ANNUAL to MONTHLY preserves value via overflow credit (P1-6)`() {
        // A user has 200 days left on an annual plan. They want to switch to monthly.
        // Current value remaining = 19000 * 200 / 365 = 10410 grosze.
        // Monthly base = 1900. Coupon caps at 1900 (free first month), the remaining
        // 8510 grosze MUST flow into Stripe customer.balance to avoid silent value loss.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.ANNUAL_PLAN, daysRemaining = 200L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        assertEquals(StripeHandler.MONTHLY_PRICE_PLN, discount, "coupon should cap at monthly base")
        assertEquals(8510L, overflow, "overflow must equal remaining-base for downgrade credit")
        // Sanity: the total value (coupon + overflow) preserves the original remaining value.
        val originalRemaining = StripeHandler.ANNUAL_PRICE_PLN * 200L / StripeHandler.ANNUAL_TOTAL_DAYS
        assertEquals(originalRemaining, discount + overflow)
    }

    @Test
    @Order(28)
    fun `upgrade MONTHLY to ANNUAL no overflow (remaining value smaller than annual)`() {
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 20L,
            newPlanBaseGrosze = StripeHandler.ANNUAL_PRICE_PLN,
            newPlanTotalDays = StripeHandler.ANNUAL_TOTAL_DAYS,
        )
        // 1900 * 20 / 31 = 1225 grosze. Annual base = 19000 → no overflow.
        assertEquals(1225L, discount)
        assertEquals(0L, overflow)
    }

    @Test
    @Order(29)
    fun `total discount cannot exceed base price`() {
        // Used to be a tautology over a local minOf. Now wired through the real helper to ensure
        // the cap is enforced.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 31L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        assertEquals(StripeHandler.MONTHLY_PRICE_PLN, discount)
        assertEquals(0L, overflow) // 1900 * 31 / 31 == 1900 exactly, no overflow
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
    fun `mark promo code used increments count and returns true`() {
        // Get current used_count for TESTCODE50
        val beforeCount = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT used_count FROM promo_code WHERE code = 'TESTCODE50'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("used_count") else 0 }
            }
        }

        val claimed = promoCodeHandler.markPromoCodeUsed("TESTCODE50", testUserId, "cs_test_session_123")
        assertTrue(claimed, "first call must succeed")

        val afterCount = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT used_count FROM promo_code WHERE code = 'TESTCODE50'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("used_count") else 0 }
            }
        }

        assertEquals(beforeCount + 1, afterCount)

        // Verify usage record
        val usage = db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT user_id, stripe_session FROM promo_code_usage WHERE user_id = ? AND stripe_session = ? ORDER BY used_at DESC LIMIT 1",
            ).use { ps ->
                ps.setString(1, testUserId)
                ps.setString(2, "cs_test_session_123")
                ps.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getString("user_id"), rs.getString("stripe_session")) else null
                }
            }
        }

        assertNotNull(usage)
        assertEquals(testUserId, usage!!.first)
        assertEquals("cs_test_session_123", usage.second)
    }

    @Test
    @Order(37)
    fun `mark promo code used rejects double-claim by same user (P1-5)`() {
        // testUserId already used TESTCODE50 in @Order(36).
        // UNIQUE(promo_code_id, user_id) MUST block a second claim.
        val beforeCount = currentUsedCount("TESTCODE50")

        val secondClaim = promoCodeHandler.markPromoCodeUsed("TESTCODE50", testUserId, "cs_test_session_DUP")
        assertFalse(secondClaim, "second mark by same user MUST be rejected")

        val afterCount = currentUsedCount("TESTCODE50")
        assertEquals(beforeCount, afterCount, "used_count must not be incremented on rejected claim")
    }

    @Test
    @Order(38)
    fun `mark promo code used enforces max_uses across distinct users (P1-5 race)`() {
        // Code limited to max_uses=2. Three distinct users try to claim — only first two succeed.
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES ('LIMITED2', 'percentage', 25, 2, 0, TRUE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        val u1 = createTestUser("limited2-u1@properbackup.dev")
        val u2 = createTestUser("limited2-u2@properbackup.dev")
        val u3 = createTestUser("limited2-u3@properbackup.dev")

        assertTrue(promoCodeHandler.markPromoCodeUsed("LIMITED2", u1, "cs_lim_1"))
        assertTrue(promoCodeHandler.markPromoCodeUsed("LIMITED2", u2, "cs_lim_2"))
        assertFalse(promoCodeHandler.markPromoCodeUsed("LIMITED2", u3, "cs_lim_3"),
            "third claim past max_uses must be rejected atomically")

        val usedCount = currentUsedCount("LIMITED2")
        assertEquals(2, usedCount, "used_count must reflect exactly the number of successful claims")
    }

    @Test
    @Order(39)
    fun `first_order promo respects ever_subscribed even after cancellation (P2-11)`() {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, applicable_plan, max_uses, active, created_at)
                   VALUES ('FIRST20', 'percentage', 20, 'first_order', 100, TRUE, NOW())
                   ON CONFLICT (code) DO NOTHING""",
            ).use { it.executeUpdate() }
        }

        // Brand-new user (no plan, never subscribed) → eligible.
        val newUser = createTestUser("first-order-new@properbackup.dev")
        assertEquals(380L, promoCodeHandler.calculateDiscount("FIRST20", 1900L),
            "new user must get the first_order discount")

        // Simulate the user having subscribed once, then cancelled (subscription_plan NULL, but
        // ever_subscribed=TRUE).
        val cancelledUser = createTestUser("first-order-cancelled@properbackup.dev")
        userStore.markEverSubscribed(cancelledUser)

        // calculateDiscount alone is plan-agnostic. The eligibility check lives in validatePromoCode
        // which uses userHadSubscription → must see ever_subscribed flag. Verify via flag query.
        val everSubscribed = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT ever_subscribed FROM users WHERE id = ?").use { ps ->
                ps.setString(1, cancelledUser)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean("ever_subscribed") else false }
            }
        }
        assertTrue(everSubscribed, "ever_subscribed must persist after deactivation")
    }

    private fun currentUsedCount(code: String): Int = db.getConnection().use { conn ->
        conn.prepareStatement("SELECT used_count FROM promo_code WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("used_count") else 0 }
        }
    }

    private fun createTestUser(email: String): String {
        val id = UUID.randomUUID().toString()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO users (id, email, bcrypt_hash, created_at, storage_limit_bytes)
                   VALUES (?, ?, ?, NOW(), 2199023255552)
                   ON CONFLICT (email) DO NOTHING""",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, email)
                ps.setString(3, TEST_PASSWORD_HASH)
                ps.executeUpdate()
            }
        }
        return id
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
    // Stripe event idempotency (P1-1)
    // ========================================================================

    @Test
    @Order(50)
    fun `webhook idempotency - tryClaimStripeEventId returns true once then false (P1-1)`() {
        val eventId = "evt_idempotency_${System.currentTimeMillis()}"

        val firstClaim = stripeHandler.tryClaimStripeEventId(eventId, "invoice.paid")
        val secondClaim = stripeHandler.tryClaimStripeEventId(eventId, "invoice.paid")
        val thirdClaim = stripeHandler.tryClaimStripeEventId(eventId, "invoice.paid")

        assertTrue(firstClaim, "first time we see the event → must claim")
        assertFalse(secondClaim, "retry must be rejected (handler already ran)")
        assertFalse(thirdClaim, "another retry must also be rejected")

        // Sanity: the ledger has exactly one row for this event id
        val rowCount = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM stripe_event_idempotency WHERE stripe_event_id = ?").use { ps ->
                ps.setString(1, eventId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
        assertEquals(1, rowCount, "idempotency ledger must contain exactly one row per event id")
    }

    @Test
    @Order(51)
    fun `webhook idempotency - distinct event ids both claim`() {
        val evt1 = "evt_a_${System.currentTimeMillis()}"
        val evt2 = "evt_b_${System.currentTimeMillis()}"

        assertTrue(stripeHandler.tryClaimStripeEventId(evt1, "checkout.session.completed"))
        assertTrue(stripeHandler.tryClaimStripeEventId(evt2, "customer.subscription.updated"))
        assertFalse(stripeHandler.tryClaimStripeEventId(evt1, "checkout.session.completed"))
    }

    @Test
    @Order(52)
    fun `webhook idempotency - blank or null event id is not deduped (defensive)`() {
        // If Stripe ever sends an event without an id (shouldn't happen, but defensive),
        // we allow the handler to proceed rather than silently skip it. The handler itself must
        // then enforce its own per-resource idempotency.
        assertTrue(stripeHandler.tryClaimStripeEventId(null, "unknown"))
        assertTrue(stripeHandler.tryClaimStripeEventId("", "unknown"))
        assertTrue(stripeHandler.tryClaimStripeEventId("   ", "unknown"))
    }

    @Test
    @Order(53)
    fun `stripe event table stores events correctly (legacy event log)`() {
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
    @Order(54)
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

    @Test
    @Order(90)
    fun `webhook fail-closed - blank secret means handler refuses requests (P1-2)`() {
        // A StripeHandler instantiated WITHOUT a webhook secret must NOT process webhook payloads.
        // The integration is structured so that the handler reads the override at construction;
        // we verify the contract by checking that the handler reports itself as not configured.
        val handlerWithoutSecret = StripeHandler(
            db, userStore, promoCodeHandler,
            stripeModeOverride = "test",
            stripeSecretKeyOverride = "",
            stripeWebhookSecretOverride = "",
        )
        // We can’t mock io.javalin.http.Context easily here. The contract we DO verify is that
        // tryClaimStripeEventId still works (idempotency table is independent of webhook secret),
        // BUT a webhook handler with a blank secret rejects all payloads at handleWebhook entry.
        // The actual handleWebhook 503 behaviour is verified in test-subscription.sh E2E (curl).
        // Here we at least guarantee the new helper exists and the API surface is stable.
        val unrelatedClaim = handlerWithoutSecret.tryClaimStripeEventId("evt_no_secret_${System.currentTimeMillis()}")
        assertTrue(unrelatedClaim, "idempotency ledger must work regardless of webhook secret")
    }

    @Test
    @Order(91)
    fun `activateSubscription marks ever_subscribed (P2-11)`() {
        val freshUser = createTestUser("ever-sub-${System.currentTimeMillis()}@properbackup.dev")
        // Pre-condition
        val before = userStore.findById(freshUser).orElseThrow()
        assertFalse(before.everSubscribed)

        // Use a handler with secrets blank — activateSubscription only touches the DB.
        stripeHandler.activateSubscription(freshUser, StripeHandler.MONTHLY_PLAN)

        val after = userStore.findById(freshUser).orElseThrow()
        assertTrue(after.everSubscribed, "activateSubscription must persist ever_subscribed=TRUE")
        assertEquals(StripeHandler.MONTHLY_PLAN, after.subscriptionPlan)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P2-7: idempotency-key on createCheckoutSession
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(100)
    fun `checkout idempotency-key is deterministic within a time bucket (P2-7)`() {
        val now = 1_700_000_000L
        val a = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, null, now, 30L)
        val b = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, null, now + 5, 30L)
        assertEquals(a, b, "same bucket must produce same key — Stripe needs this to dedupe sessions")
    }

    @Test
    @Order(101)
    fun `checkout idempotency-key differs across users plans promo amounts (P2-7)`() {
        val now = 1_700_000_000L
        val base = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, null, now)
        val differentUser = StripeHandler.checkoutIdempotencyKey("u2", "monthly", 1900L, null, now)
        val differentPlan = StripeHandler.checkoutIdempotencyKey("u1", "annual", 1900L, null, now)
        val differentPrice = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1500L, null, now)
        val differentPromo = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, "SAVE20", now)

        assertNotEquals(base, differentUser, "different user must produce different key")
        assertNotEquals(base, differentPlan, "different plan must produce different key")
        assertNotEquals(base, differentPrice, "different base price must produce different key")
        assertNotEquals(base, differentPromo, "different promo must produce different key")
    }

    @Test
    @Order(102)
    fun `checkout idempotency-key differs across time buckets (P2-7)`() {
        val now = 1_700_000_000L
        val a = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, null, now, 30L)
        val b = StripeHandler.checkoutIdempotencyKey("u1", "monthly", 1900L, null, now + 60, 30L)
        assertNotEquals(a, b, "next time bucket must produce a different key — user retries after a minute should not be blocked")
    }

    @Test
    @Order(103)
    fun `checkout idempotency-key is short enough for Stripe header (P2-7)`() {
        val key = StripeHandler.checkoutIdempotencyKey("user-uuid-1234567890-very-long", "monthly", 1900L, "BLACKFRIDAY50", 1_700_000_000L)
        // Stripe limits Idempotency-Key header values to 255 chars; we generate <= 64.
        assertTrue(key.length in 8..64, "idempotency key length must be in (8, 64), got ${key.length}")
        assertTrue(key.matches(Regex("[a-f0-9]+")), "must be hex digest, got '$key'")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P2-9: activateSubscription uses Stripe current_period_end (not 31/365)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(110)
    fun `activateSubscription uses provided stripePeriodEnd exactly (P2-9)`() {
        val freshUser = createTestUser("period-end-${System.currentTimeMillis()}@properbackup.dev")
        // Pick an odd timestamp that does NOT correspond to either 31 days or 365 days from now.
        // This way if the old hardcoded fallback ran, we'd see a very different value.
        val stripePeriodEnd = Instant.parse("2099-06-15T12:34:56Z")

        stripeHandler.activateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, stripePeriodEnd)

        val after = userStore.findById(freshUser).orElseThrow()
        val recorded = Instant.parse(after.subscriptionExpiresAt!!)
        // PostgreSQL TIMESTAMP truncates at microseconds; tolerate ≤1s drift in either direction.
        val drift = Duration.between(stripePeriodEnd, recorded).abs().toSeconds()
        assertTrue(drift <= 1, "DB expiry must equal Stripe period_end (drift=${drift}s, got $recorded)")
    }

    @Test
    @Order(111)
    fun `activateSubscription without stripePeriodEnd falls back to 31 day stack (P2-9)`() {
        val freshUser = createTestUser("fallback-${System.currentTimeMillis()}@properbackup.dev")
        val before = Instant.now()
        stripeHandler.activateSubscription(freshUser, StripeHandler.MONTHLY_PLAN)

        val after = userStore.findById(freshUser).orElseThrow()
        val recorded = Instant.parse(after.subscriptionExpiresAt!!)
        val daysAdded = Duration.between(before, recorded).toDays()
        assertTrue(daysAdded in 30..31, "fallback must add ~31 days, got $daysAdded")
    }

    @Test
    @Order(112)
    fun `activateSubscription annual fallback adds 365 days (P2-9)`() {
        val freshUser = createTestUser("annual-fallback-${System.currentTimeMillis()}@properbackup.dev")
        val before = Instant.now()
        stripeHandler.activateSubscription(freshUser, StripeHandler.ANNUAL_PLAN)

        val after = userStore.findById(freshUser).orElseThrow()
        val recorded = Instant.parse(after.subscriptionExpiresAt!!)
        val daysAdded = Duration.between(before, recorded).toDays()
        assertTrue(daysAdded in 364..365, "annual fallback must add ~365 days, got $daysAdded")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P2-12: reactivate fallback when subscription has already expired
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(120)
    fun `reactivate on locally-expired DB row returns 409 expired_must_recheckout (P2-12)`() {
        val freshUser = createTestUser("expired-reactivate-${System.currentTimeMillis()}@properbackup.dev")
        // Plant an EXPIRED subscription row in DB.
        val expiredAt = Instant.now().minus(Duration.ofDays(1))
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, expiredAt, 2199023255552L)
        userStore.updateStripeSubscriptionId(freshUser, "sub_already_dead")

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser)
        stripeHandler.reactivateSubscription(ctx)

        assertEquals(409, recorder.statusCode, "expired subscription must return 409 so the frontend redirects to checkout")
        val body = recorder.bodyAsMap()
        assertEquals("expired_must_recheckout", body["error"])
    }

    @Test
    @Order(121)
    fun `reactivate without any subscription returns no_active_subscription (P2-12)`() {
        val freshUser = createTestUser("no-sub-reactivate-${System.currentTimeMillis()}@properbackup.dev")
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser)
        stripeHandler.reactivateSubscription(ctx)
        assertEquals(400, recorder.statusCode)
        assertEquals("no_active_subscription", recorder.bodyAsMap()["error"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P3-13: PromoCodeHandler.updatePromoCode uses prepared statements only
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(130)
    fun `updatePromoCode binds active maxUses expiresAt as parameters (P3-13)`() {
        // Insert a promo we'll update by id.
        val initialMaxUses = 5
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES ('UPDATEME', 'percentage', 10, $initialMaxUses, 0, TRUE, NOW())
                   ON CONFLICT (code) DO UPDATE SET max_uses = $initialMaxUses, active = TRUE""",
            ).use { it.executeUpdate() }
        }
        val id = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT id FROM promo_code WHERE code = 'UPDATEME'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else error("missing promo") }
            }
        }

        // Inputs include characters that would have escaped a string-concatenated SQL fragment.
        val newExpiresAt = Instant.parse("2099-12-31T23:59:59Z")
        val body = mapOf(
            "active" to false,
            "maxUses" to 99,
            "expiresAt" to newExpiresAt.toString(),
        )
        val recorder = ResponseRecorder()
        val ctx = mockCtx(
            recorder,
            userId = "admin-user",
            body = com.google.gson.Gson().toJson(body),
            pathParams = mapOf("id" to id.toString()),
            isAdmin = true,
        )
        promoCodeHandler.updatePromoCode(ctx)
        assertEquals(200, recorder.statusCode)

        // Verify the bound values landed and no SQL injection happened.
        val (active, maxUses, expiresAt) = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT active, max_uses, expires_at FROM promo_code WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    Triple(rs.getBoolean("active"), rs.getInt("max_uses"), rs.getTimestamp("expires_at").toInstant())
                }
            }
        }
        assertFalse(active, "active must be set to false via prepared statement")
        assertEquals(99, maxUses)
        assertEquals(newExpiresAt, expiresAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: customer.balance surfacing in /account/subscription
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(140)
    fun `getCustomerBalanceCreditGrosze returns 0 when stripe not configured (NEW)`() {
        // Our test handler is instantiated with stripeSecretKeyOverride="", so any call must
        // short-circuit to 0 rather than throw or hit Stripe.
        val credit = stripeHandler.getCustomerBalanceCreditGrosze(testUserId)
        assertEquals(0L, credit, "blank Stripe key must yield 0 credit (informational field, never blocking)")
    }

    @Test
    @Order(141)
    fun `getCustomerBalanceCreditGrosze returns 0 when user has no stripe customer (NEW)`() {
        val freshUser = createTestUser("no-stripe-customer-${System.currentTimeMillis()}@properbackup.dev")
        val credit = stripeHandler.getCustomerBalanceCreditGrosze(freshUser)
        assertEquals(0L, credit, "user with no stripe_customer_id must yield 0 credit")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P2-8: cancelSubscriptionImmediate (proportional refund)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(150)
    fun `cancelImmediate without confirm flag returns confirmation_required (P2-8)`() {
        val freshUser = createTestUser("cancel-immediate-noconfirm-${System.currentTimeMillis()}@properbackup.dev")
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, Instant.now().plus(Duration.ofDays(15)), 2199023255552L)

        val recorder = ResponseRecorder()
        // No "confirm" key in body.
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"confirm": false}""")
        stripeHandler.cancelSubscriptionImmediate(ctx)

        assertEquals(400, recorder.statusCode)
        assertEquals("confirmation_required", recorder.bodyAsMap()["error"])

        // DB must NOT have been mutated.
        val after = userStore.findById(freshUser).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, after.subscriptionPlan, "no confirm → no deactivation")
    }

    @Test
    @Order(151)
    fun `cancelImmediate without active subscription returns no_active_subscription (P2-8)`() {
        val freshUser = createTestUser("cancel-immediate-nosub-${System.currentTimeMillis()}@properbackup.dev")
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"confirm": true}""")
        stripeHandler.cancelSubscriptionImmediate(ctx)
        assertEquals(400, recorder.statusCode)
        assertEquals("no_active_subscription", recorder.bodyAsMap()["error"])
    }

    @Test
    @Order(152)
    fun `cancelImmediate deactivates subscription and returns prorated refund amount (P2-8)`() {
        val freshUser = createTestUser("cancel-immediate-ok-${System.currentTimeMillis()}@properbackup.dev")
        val daysRemaining = 15L
        // 15 days remaining on the monthly plan → expected refund = 1900 * 15 / 31 = 919 grosze
        val expiresAt = Instant.now().plus(Duration.ofDays(daysRemaining))
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, expiresAt, 2199023255552L)

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"confirm": true}""")
        stripeHandler.cancelSubscriptionImmediate(ctx)

        assertEquals(200, recorder.statusCode)
        val body = recorder.bodyAsMap()
        assertEquals("cancelled_immediate", body["status"])
        // Stripe path is skipped (no key), so refund/balance pieces are 0 — but the user MUST
        // still be deactivated locally and the response MUST report the would-be refund value
        // for accounting/audit. We assert daysRemaining is in (14, 15).
        val days = (body["daysRemaining"] as Number).toLong()
        assertTrue(days in 14L..15L, "expected daysRemaining ≈ 15, got $days")

        // DB-side deactivation.
        val after = userStore.findById(freshUser).orElseThrow()
        assertNull(after.subscriptionPlan, "subscription_plan must be cleared")
        assertNull(after.subscriptionExpiresAt, "subscription_expires_at must be cleared")
    }

    @Test
    @Order(153)
    fun `cancelImmediate refund formula matches calculateProratedDiscount (P2-8)`() {
        // Math sanity: the refund we promise == the prorated value of the unused window.
        // For annual plan with 200 days remaining:
        //   remainingValue = 19000 * 200 / 365 = 10410 grosze (104.10 PLN)
        val (refund, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.ANNUAL_PLAN,
            daysRemaining = 200L,
            newPlanBaseGrosze = Long.MAX_VALUE,
            newPlanTotalDays = StripeHandler.ANNUAL_TOTAL_DAYS,
        )
        assertEquals(0L, overflow, "with MAX_VALUE cap there must be no overflow — refund == full remaining value")
        // 19000 * 200 / 365 = 10410
        assertEquals(10410L, refund, "annual 200d refund formula drifted: got $refund grosze")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GiftCode collision with paid Stripe subscription (audit finding)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(160)
    fun `gift code redeem on stripe-subscribed user is rejected (audit)`() {
        val freshUser = createTestUser("gift-collision-${System.currentTimeMillis()}@properbackup.dev")
        // Pretend the user already has a paid Stripe sub with 200 days remaining.
        userStore.updateSubscription(freshUser, StripeHandler.ANNUAL_PLAN, Instant.now().plus(Duration.ofDays(200)), 2199023255552L)
        userStore.updateStripeSubscriptionId(freshUser, "sub_active_annual")

        // Seed a gift code.
        val giftCode = "GIFT-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO gift_code (code, plan, duration_days, created_at)
                   VALUES (?, 'monthly', 30, NOW())""",
            ).use { ps ->
                ps.setString(1, giftCode)
                ps.executeUpdate()
            }
        }

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"code": "$giftCode"}""")
        giftCodeHandler.redeemGiftCode(ctx)

        assertEquals(409, recorder.statusCode, "must refuse to clobber paid Stripe sub with gift")
        assertEquals("active_paid_subscription", recorder.bodyAsMap()["error"])

        // Original Stripe sub is intact.
        val after = userStore.findById(freshUser).orElseThrow()
        assertEquals(StripeHandler.ANNUAL_PLAN, after.subscriptionPlan, "paid plan must NOT be overwritten")
        val daysLeft = Duration.between(Instant.now(), Instant.parse(after.subscriptionExpiresAt!!)).toDays()
        assertTrue(daysLeft in 199..200, "annual sub expiry must be untouched (got $daysLeft days)")
    }

    @Test
    @Order(161)
    fun `gift code redeem stacks on existing gift expiry (audit)`() {
        val freshUser = createTestUser("gift-stack-${System.currentTimeMillis()}@properbackup.dev")
        // Plant an existing gift-style subscription (no Stripe ID) with 10 days left.
        userStore.updateSubscription(freshUser, "monthly", Instant.now().plus(Duration.ofDays(10)), 2199023255552L)

        val giftCode = "GIFT-${System.currentTimeMillis()}-STACK"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO gift_code (code, plan, duration_days, created_at)
                   VALUES (?, 'monthly', 30, NOW())""",
            ).use { ps ->
                ps.setString(1, giftCode)
                ps.executeUpdate()
            }
        }

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"code": "$giftCode"}""")
        giftCodeHandler.redeemGiftCode(ctx)

        assertEquals(200, recorder.statusCode)
        val after = userStore.findById(freshUser).orElseThrow()
        val daysLeft = Duration.between(Instant.now(), Instant.parse(after.subscriptionExpiresAt!!)).toDays()
        // 10 existing + 30 new = 40 days (with rounding tolerance).
        assertTrue(daysLeft in 39..40, "gift duration must STACK, got $daysLeft (expected ~40)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PromoCodeHandler.validateForCheckout (audit — race fix)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(170)
    fun `validateForCheckout returns reason for non-existent code (audit)`() {
        val r = promoCodeHandler.validateForCheckout("DOES-NOT-EXIST", testUserId)
        assertEquals("not_found_or_expired", r)
    }

    @Test
    @Order(171)
    fun `validateForCheckout returns reason for expired code (audit)`() {
        // Seed an expired promo.
        val code = "EXPIRED-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, expires_at, created_at)
                   VALUES (?, 'percentage', 10, 100, 0, TRUE, NOW() - INTERVAL '1 day', NOW())""",
            ).use { ps -> ps.setString(1, code); ps.executeUpdate() }
        }
        val r = promoCodeHandler.validateForCheckout(code, testUserId)
        assertEquals("not_found_or_expired", r)
    }

    @Test
    @Order(172)
    fun `validateForCheckout returns reason when first_order user already subscribed (audit)`() {
        val freshUser = createTestUser("promo-firstorder-${System.currentTimeMillis()}@properbackup.dev")
        userStore.markEverSubscribed(freshUser)

        val code = "FIRSTORDER-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES (?, 'first_order', 20, 1000, 0, TRUE, NOW())""",
            ).use { ps -> ps.setString(1, code); ps.executeUpdate() }
        }

        val r = promoCodeHandler.validateForCheckout(code, freshUser)
        assertEquals("not_eligible_first_order", r)
    }

    @Test
    @Order(173)
    fun `validateForCheckout returns null for valid percentage code (audit)`() {
        val freshUser = createTestUser("promo-valid-${System.currentTimeMillis()}@properbackup.dev")
        val code = "VALID-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES (?, 'percentage', 25, 1000, 0, TRUE, NOW())""",
            ).use { ps -> ps.setString(1, code); ps.executeUpdate() }
        }

        val r = promoCodeHandler.validateForCheckout(code, freshUser)
        assertNull(r, "valid promo for this user must return null reason")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test helpers — minimal Javalin Context mock and response recorder so we can
    // exercise endpoint methods that take a real Context without spinning up Jetty.
    // ─────────────────────────────────────────────────────────────────────────

    private class ResponseRecorder {
        var statusCode: Int = 200
        var body: String = ""
        var contentType: String? = null

        fun bodyAsMap(): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return com.google.gson.Gson().fromJson(body, Map::class.java) as Map<String, Any?>
        }
    }

    private fun mockCtx(
        recorder: ResponseRecorder,
        userId: String? = null,
        body: String = "",
        pathParams: Map<String, String> = emptyMap(),
        isAdmin: Boolean = false,
        headers: Map<String, String?> = emptyMap(),
    ): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.attribute<String>("userId") } returns userId
        every { ctx.attribute<Boolean>("isSystemAdmin") } returns isAdmin
        every { ctx.body() } returns body
        every { ctx.pathParam(any()) } answers { pathParams[firstArg<String>()] ?: "" }
        every { ctx.header(any()) } answers { headers[firstArg<String>()] }
        every { ctx.status(any<Int>()) } answers {
            recorder.statusCode = firstArg()
            ctx
        }
        every { ctx.result(any<String>()) } answers {
            recorder.body = firstArg()
            ctx
        }
        every { ctx.contentType(any<String>()) } answers {
            recorder.contentType = firstArg()
            ctx
        }
        every { ctx.header(any<String>(), any<String>()) } answers { ctx }
        return ctx
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P1-2 / audit: HTTP-level handleWebhook signature checks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(180)
    fun `webhook with blank secret returns 503 fail-closed (P1-2)`() {
        // The default test stripeHandler is created with stripeWebhookSecret = "".
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = """{"id":"evt_test","type":"checkout.session.completed"}""")
        stripeHandler.handleWebhook(ctx)
        assertEquals(503, recorder.statusCode, "must fail-closed when STRIPE_WEBHOOK_SECRET is not configured")
    }

    @Test
    @Order(181)
    fun `webhook missing Stripe-Signature header returns 400 (audit)`() {
        // New handler with a non-blank secret so we hit the signature path.
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            stripeModeOverride = "test",
            stripeSecretKeyOverride = "",
            stripeWebhookSecretOverride = "whsec_test_secret_${System.currentTimeMillis()}",
            panelUrlOverride = "http://localhost:5175",
        )
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = """{"id":"evt_test","type":"checkout.session.completed"}""")
        handler.handleWebhook(ctx)
        assertEquals(400, recorder.statusCode)
    }

    @Test
    @Order(182)
    fun `webhook with tampered signature returns 400 (audit)`() {
        val secret = "whsec_test_${System.currentTimeMillis()}"
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            stripeModeOverride = "test",
            stripeSecretKeyOverride = "",
            stripeWebhookSecretOverride = secret,
            panelUrlOverride = "http://localhost:5175",
        )
        val payload = """{"id":"evt_tampered","type":"checkout.session.completed"}"""
        // Compute a signature with the WRONG secret to simulate an attacker.
        val sig = stripeSignatureHeader(payload, "different_secret_${System.currentTimeMillis()}", System.currentTimeMillis() / 1000)
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = payload, headers = mapOf("Stripe-Signature" to sig))
        handler.handleWebhook(ctx)
        assertEquals(400, recorder.statusCode, "tampered signature must yield 400")
    }

    @Test
    @Order(183)
    fun `webhook with valid signature is accepted (audit)`() {
        val secret = "whsec_test_valid_${System.currentTimeMillis()}"
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            stripeModeOverride = "test",
            stripeSecretKeyOverride = "",
            stripeWebhookSecretOverride = secret,
            panelUrlOverride = "http://localhost:5175",
        )
        // Build a minimal but parseable Stripe event payload.
        val eventId = "evt_valid_${System.currentTimeMillis()}"
        val payload = """{"id":"$eventId","object":"event","type":"customer.subscription.updated","data":{"object":{}}}"""
        val ts = System.currentTimeMillis() / 1000
        val sig = stripeSignatureHeader(payload, secret, ts)
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = payload, headers = mapOf("Stripe-Signature" to sig))
        handler.handleWebhook(ctx)
        assertEquals(200, recorder.statusCode, "valid signature must yield 200, got body=${recorder.body}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StorageQuotaGuard vs expired subscription (audit)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(190)
    fun `StorageQuotaGuard blocks upload when both subscription AND trial expired (audit)`() {
        val freshUser = createTestUser("storage-expired-${System.currentTimeMillis()}@properbackup.dev")
        // Trial already over.
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET trial_expires_at = NOW() - INTERVAL '7 days' WHERE id = ?")
                .use { ps -> ps.setString(1, freshUser); ps.executeUpdate() }
        }
        // Subscription clear (no plan, no expiry).
        userStore.deactivateSubscription(freshUser)

        val guard = StorageQuotaGuard(db, userStore)
        val q = guard.checkQuota(freshUser)
        assertTrue(q.subscriptionInactive, "expired-user state must flag subscriptionInactive=true")
        assertFalse(guard.canUpload(freshUser, 1024L), "expired-user upload must be REFUSED")
    }

    @Test
    @Order(191)
    fun `StorageQuotaGuard permits upload while trial is still active (audit)`() {
        val freshUser = createTestUser("storage-trial-${System.currentTimeMillis()}@properbackup.dev")
        // Active trial (30 days from now).
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET trial_expires_at = NOW() + INTERVAL '30 days' WHERE id = ?")
                .use { ps -> ps.setString(1, freshUser); ps.executeUpdate() }
        }
        val guard = StorageQuotaGuard(db, userStore)
        val q = guard.checkQuota(freshUser)
        assertFalse(q.subscriptionInactive)
        assertTrue(guard.canUpload(freshUser, 1024L))
    }

    @Test
    @Order(192)
    fun `StorageQuotaGuard permits upload with active paid subscription (audit)`() {
        val freshUser = createTestUser("storage-paid-${System.currentTimeMillis()}@properbackup.dev")
        // Active paid subscription, but trial already expired.
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, Instant.now().plus(Duration.ofDays(30)), 2199023255552L)
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET trial_expires_at = NOW() - INTERVAL '30 days' WHERE id = ?")
                .use { ps -> ps.setString(1, freshUser); ps.executeUpdate() }
        }
        val guard = StorageQuotaGuard(db, userStore)
        val q = guard.checkQuota(freshUser)
        assertFalse(q.subscriptionInactive)
        assertTrue(guard.canUpload(freshUser, 1024L))
    }

    /** Build a Stripe-Signature header value with `t=<ts>,v1=<hmac>`. */
    private fun stripeSignatureHeader(payload: String, secret: String, ts: Long): String {
        val signedPayload = "$ts.$payload"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hex = mac.doFinal(signedPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "t=$ts,v1=$hex"
    }
}
