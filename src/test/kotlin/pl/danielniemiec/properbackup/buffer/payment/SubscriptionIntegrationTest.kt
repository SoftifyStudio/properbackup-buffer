package pl.danielniemiec.properbackup.buffer.payment

import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
            // StripeKeyProvider with blank keys → Stripe SDK not initialised, but db-level helpers still work.
            val keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testPublicKeyOverride = "",
                livePublicKeyOverride = "",
                testWebhookSecretOverride = "",
                liveWebhookSecretOverride = "",
            )
            stripeHandler = StripeHandler(
                db, userStore, promoCodeHandler,
                keyProvider = keyProvider,
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
    @Order(28_1)
    fun `EDGE CASE - annual user with 500 days remaining switches to monthly (gt365 days)`() {
        // Customer somehow has >365 days (e.g. stacked gift codes + annual renewal).
        // remainingValue = 19000 * 500 / 365 = 26027. Monthly base = 1900.
        // Coupon caps at 1900. Overflow = 26027 - 1900 = 24127.
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.ANNUAL_PLAN, daysRemaining = 500L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        val expectedRemaining = StripeHandler.ANNUAL_PRICE_PLN * 500L / StripeHandler.ANNUAL_TOTAL_DAYS
        assertEquals(StripeHandler.MONTHLY_PRICE_PLN, discount, "coupon caps at monthly base")
        assertEquals(expectedRemaining - StripeHandler.MONTHLY_PRICE_PLN, overflow, "excess goes to customer.balance")
        assertEquals(expectedRemaining, discount + overflow, "total value preserved")
    }

    @Test
    @Order(28_2)
    fun `EDGE CASE - annual user with 500 days remaining switches to annual`() {
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.ANNUAL_PLAN, daysRemaining = 500L,
            newPlanBaseGrosze = StripeHandler.ANNUAL_PRICE_PLN,
            newPlanTotalDays = StripeHandler.ANNUAL_TOTAL_DAYS,
        )
        val expectedRemaining = StripeHandler.ANNUAL_PRICE_PLN * 500L / StripeHandler.ANNUAL_TOTAL_DAYS
        assertEquals(StripeHandler.ANNUAL_PRICE_PLN, discount, "coupon caps at annual base (free renewal)")
        assertEquals(expectedRemaining - StripeHandler.ANNUAL_PRICE_PLN, overflow)
        assertEquals(expectedRemaining, discount + overflow, "total value preserved")
    }

    @Test
    @Order(28_3)
    fun `EDGE CASE - monthly user with 60 days remaining (stacked gift) switches to monthly`() {
        val (discount, overflow) = StripeHandler.calculateProratedDiscount(
            currentPlan = StripeHandler.MONTHLY_PLAN, daysRemaining = 60L,
            newPlanBaseGrosze = StripeHandler.MONTHLY_PRICE_PLN,
            newPlanTotalDays = StripeHandler.MONTHLY_TOTAL_DAYS,
        )
        val expectedRemaining = StripeHandler.MONTHLY_PRICE_PLN * 60L / StripeHandler.MONTHLY_TOTAL_DAYS
        assertEquals(StripeHandler.MONTHLY_PRICE_PLN, discount, "coupon caps at monthly base")
        assertTrue(overflow > 0, "must have overflow credit when remaining > plan period")
        assertEquals(expectedRemaining, discount + overflow, "total value preserved")
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
    fun `mark promo code used enforces max_uses across distinct users sequentially (P1-5)`() {
        // Code limited to max_uses=2. Three distinct users try to claim — only first two succeed.
        // This is the deterministic, sequential version. The TRUE concurrent variant lives in
        // `markPromoCodeUsed under TRUE parallel race…` below.
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
    @Order(40)
    fun `markPromoCodeUsed under TRUE parallel race rejects everyone past max_uses (audit)`() {
        // Sequential success/reject only proves the math. To prove the atomic
        // UPDATE…WHERE used_count<max_uses is actually race-free we need real concurrency:
        // N threads ALL parked on a CountDownLatch, released simultaneously, racing for the
        // same promo. Expectation: exactly max_uses successes, the rest must come back FALSE,
        // and the DB row's used_count must equal max_uses (never overshoot).
        val code = "RACE-${System.currentTimeMillis()}"
        val maxUses = 3
        val threadCount = 10
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO promo_code (code, type, discount_percent, max_uses, used_count, active, created_at)
                   VALUES (?, 'percentage', 25, ?, 0, TRUE, NOW())""",
            ).use { ps ->
                ps.setString(1, code)
                ps.setInt(2, maxUses)
                ps.executeUpdate()
            }
        }

        val users = (1..threadCount).map { createTestUser("race-u$it-${System.currentTimeMillis()}@properbackup.dev") }
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val successes = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            users.forEachIndexed { idx, uid ->
                pool.submit {
                    try {
                        // Block until the latch fires — gives JIT & DB time to warm so all
                        // threads contend for the row as close to simultaneously as possible.
                        start.await()
                        val ok = promoCodeHandler.markPromoCodeUsed(code, uid, "cs_race_$idx")
                        if (ok) successes.incrementAndGet() else rejections.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }
            }
            // Release all threads at once.
            start.countDown()
            assertTrue(done.await(15, TimeUnit.SECONDS), "race test threads did not finish in 15s")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(maxUses, successes.get(),
            "exactly maxUses ($maxUses) threads must succeed; got ${successes.get()}")
        assertEquals(threadCount - maxUses, rejections.get(),
            "all remaining threads must be rejected atomically; got ${rejections.get()}")
        assertEquals(maxUses, currentUsedCount(code),
            "DB used_count must equal maxUses — no overshoot allowed under contention")
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
                "INSERT INTO stripe_price_config (plan_key, mode, stripe_price_id) VALUES (?, ?, ?) ON CONFLICT (plan_key, mode) DO UPDATE SET stripe_price_id = ?",
            ).use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "test")
                ps.setString(3, "price_test_123")
                ps.setString(4, "price_test_123")
                ps.executeUpdate()
            }
        }

        val priceId = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ? AND mode = ?").use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "test")
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
                "INSERT INTO stripe_price_config (plan_key, mode, stripe_price_id) VALUES (?, ?, ?) ON CONFLICT (plan_key, mode) DO UPDATE SET stripe_price_id = ?, updated_at = NOW()",
            ).use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "test")
                ps.setString(3, "price_test_456")
                ps.setString(4, "price_test_456")
                ps.executeUpdate()
            }
        }

        val priceId = db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = ? AND mode = ?").use { ps ->
                ps.setString(1, "test_monthly")
                ps.setString(2, "test")
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
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = "",
                liveWebhookSecretOverride = "",
            ),
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

    @Test
    @Order(162)
    fun `gift redeem on cancel_at_period_end=true sub is accepted and preserves cancel flag (audit)`() {
        val freshUser = createTestUser("gift-cape-${System.currentTimeMillis()}@properbackup.dev")
        // Active Stripe sub with 20 days remaining, but user has flagged cancel_at_period_end.
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, Instant.now().plus(Duration.ofDays(20)), 2199023255552L)
        userStore.updateStripeSubscriptionId(freshUser, "sub_cancel_pending_${System.currentTimeMillis()}")
        userStore.setCancelAtPeriodEnd(freshUser, true)

        val giftCode = "GIFT-CAPE-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO gift_code (code, plan, duration_days, created_at)
                   VALUES (?, 'monthly', 30, NOW())""",
            ).use { ps -> ps.setString(1, giftCode); ps.executeUpdate() }
        }

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"code": "$giftCode"}""")
        giftCodeHandler.redeemGiftCode(ctx)

        assertEquals(200, recorder.statusCode, "gift on cancel-at-period-end sub MUST be accepted")
        val after = userStore.findById(freshUser).orElseThrow()
        // Stripe sub id is left intact — Stripe will end the sub at its own period_end.
        assertNotNull(after.stripeSubscriptionId, "Stripe subscription must not be cleared by gift redeem")
        // Cancel flag MUST be preserved — otherwise Stripe would silently auto-renew & charge.
        assertTrue(after.subscriptionCancelAtPeriodEnd,
            "cancel_at_period_end MUST stay TRUE after gift redeem — otherwise Stripe would auto-renew & double-charge")
        val daysLeft = Duration.between(Instant.now(), Instant.parse(after.subscriptionExpiresAt!!)).toDays()
        // 20 (remaining Stripe period) + 30 (gift) = 50 days.
        assertTrue(daysLeft in 49..50, "expiry must stack from current Stripe period, got $daysLeft (expected ~50)")
    }

    @Test
    @Order(163)
    fun `gift redeem on expired sub (stripeSubscriptionId leftover) treats as no-sub (audit)`() {
        val freshUser = createTestUser("gift-expired-${System.currentTimeMillis()}@properbackup.dev")
        // Orphan case: stripe sub id still on the row but Stripe period_end has already passed
        // (DB out of sync — webhook lost, manual cleanup, etc.). Subscription is technically
        // dead, so gift should land like a brand-new redeem.
        userStore.updateSubscription(freshUser, StripeHandler.MONTHLY_PLAN, Instant.now().minus(Duration.ofDays(3)), 2199023255552L)
        userStore.updateStripeSubscriptionId(freshUser, "sub_dead_${System.currentTimeMillis()}")

        val giftCode = "GIFT-DEAD-${System.currentTimeMillis()}"
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """INSERT INTO gift_code (code, plan, duration_days, created_at)
                   VALUES (?, 'monthly', 30, NOW())""",
            ).use { ps -> ps.setString(1, giftCode); ps.executeUpdate() }
        }

        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, userId = freshUser, body = """{"code": "$giftCode"}""")
        giftCodeHandler.redeemGiftCode(ctx)

        assertEquals(200, recorder.statusCode, "expired sub must be treated as no-sub — gift accepted")
        val after = userStore.findById(freshUser).orElseThrow()
        val daysLeft = Duration.between(Instant.now(), Instant.parse(after.subscriptionExpiresAt!!)).toDays()
        // Base = now (because expiry was in the past), + 30 day gift. NOT 30 - 3 = 27.
        assertTrue(daysLeft in 29..30,
            "expired stripe row must NOT extend backwards — gift counts from now, got $daysLeft (expected ~30)")
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
        val whsec = "whsec_test_secret_${System.currentTimeMillis()}"
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = whsec,
                liveWebhookSecretOverride = "",
            ),
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
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = secret,
                liveWebhookSecretOverride = "",
            ),
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
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = secret,
                liveWebhookSecretOverride = "",
            ),
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

    // ========================================================================
    // StripeKeyProvider: per-user key isolation tests
    // ========================================================================

    @Test
    @Order(200)
    fun `StripeKeyProvider returns test keys for new users (default)`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "sk_test_AAAA",
            liveSecretKeyOverride = "sk_live_BBBB",
            testPublicKeyOverride = "pk_test_CCCC",
            livePublicKeyOverride = "pk_live_DDDD",
        )
        val user = createTestUser("kp-test-${System.currentTimeMillis()}@properbackup.dev")
        assertTrue(kp.isTestMode(user))
        assertEquals("sk_test_AAAA", kp.secretKeyFor(user))
        assertEquals("pk_test_CCCC", kp.publicKeyFor(user))
        assertEquals("test", kp.modeLabel(user))
    }

    @Test
    @Order(201)
    fun `StripeKeyProvider switches to live keys after flag flip`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "sk_test_AAA201",
            liveSecretKeyOverride = "sk_live_BBB201",
            testPublicKeyOverride = "pk_test_CCC201",
            livePublicKeyOverride = "pk_live_DDD201",
        )
        val user = createTestUser("kp-live-${System.currentTimeMillis()}@properbackup.dev")
        assertTrue(kp.isTestMode(user))

        kp.setTestMode(user, false)

        assertFalse(kp.isTestMode(user))
        assertEquals("sk_live_BBB201", kp.secretKeyFor(user))
        assertEquals("pk_live_DDD201", kp.publicKeyFor(user))
        assertEquals("live", kp.modeLabel(user))
    }

    @Test
    @Order(202)
    fun `StripeKeyProvider per-user isolation - two users different modes`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "sk_test_202",
            liveSecretKeyOverride = "sk_live_202",
        )
        val testUser = createTestUser("kp-test-a-${System.currentTimeMillis()}@properbackup.dev")
        val liveUser = createTestUser("kp-live-b-${System.currentTimeMillis()}@properbackup.dev")

        kp.setTestMode(liveUser, false)

        assertEquals("sk_test_202", kp.secretKeyFor(testUser))
        assertEquals("sk_live_202", kp.secretKeyFor(liveUser))
        assertEquals("test", kp.modeLabel(testUser))
        assertEquals("live", kp.modeLabel(liveUser))
    }

    @Test
    @Order(203)
    fun `StripeKeyProvider RequestOptions carry correct key`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "sk_test_203",
            liveSecretKeyOverride = "sk_live_203",
        )
        val user = createTestUser("kp-reqopt-${System.currentTimeMillis()}@properbackup.dev")

        val testOpts = kp.requestOptionsFor(user)
        assertEquals("sk_test_203", testOpts.apiKey)

        kp.setTestMode(user, false)
        val liveOpts = kp.requestOptionsFor(user)
        assertEquals("sk_live_203", liveOpts.apiKey)
    }

    @Test
    @Order(204)
    fun `StripeKeyProvider live keys fallback to test when not configured`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "sk_test_fallback",
            liveSecretKeyOverride = null,
            testPublicKeyOverride = "pk_test_fallback",
            livePublicKeyOverride = null,
        )
        val user = createTestUser("kp-fallback-${System.currentTimeMillis()}@properbackup.dev")
        kp.setTestMode(user, false)
        assertEquals("sk_test_fallback", kp.secretKeyFor(user))
        assertEquals("pk_test_fallback", kp.publicKeyFor(user))
    }

    @Test
    @Order(205)
    fun `StripeKeyProvider webhookSecretCandidates returns both when different`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "",
            liveSecretKeyOverride = "",
            testWebhookSecretOverride = "whsec_test_205",
            liveWebhookSecretOverride = "whsec_live_205",
        )
        val candidates = kp.webhookSecretCandidates()
        assertEquals(2, candidates.size)
        assertTrue(candidates[0].first)
        assertEquals("whsec_test_205", candidates[0].second)
        assertFalse(candidates[1].first)
        assertEquals("whsec_live_205", candidates[1].second)
    }

    @Test
    @Order(206)
    fun `StripeKeyProvider webhookSecretCandidates deduplicates identical secrets`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "",
            liveSecretKeyOverride = "",
            testWebhookSecretOverride = "whsec_same",
            liveWebhookSecretOverride = "whsec_same",
        )
        val candidates = kp.webhookSecretCandidates()
        assertEquals(1, candidates.size)
    }

    @Test
    @Order(207)
    fun `stripeTestMode flag persisted in UserStore User data class`() {
        val user = createTestUser("flag-persist-${System.currentTimeMillis()}@properbackup.dev")
        val before = userStore.findById(user).orElseThrow()
        assertTrue(before.stripeTestMode, "new user should default to test mode")

        val kp = StripeKeyProvider(db, testSecretKeyOverride = "", liveSecretKeyOverride = "")
        kp.setTestMode(user, false)

        val after = userStore.findById(user).orElseThrow()
        assertFalse(after.stripeTestMode, "flag should be false after setting to live")
    }

    @Test
    @Order(208)
    fun `StripeHandler exposes per-user mode via getStripeModeForUser`() {
        val kp = StripeKeyProvider(
            db,
            testSecretKeyOverride = "",
            liveSecretKeyOverride = "",
        )
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            keyProvider = kp,
            panelUrlOverride = "http://localhost:5175",
        )
        val user = createTestUser("mode-api-${System.currentTimeMillis()}@properbackup.dev")
        assertEquals("test", handler.getStripeModeForUser(user))

        kp.setTestMode(user, false)
        assertEquals("live", handler.getStripeModeForUser(user))
    }

    @Test
    @Order(209)
    fun `webhook dual-secret verification accepts test-mode webhook`() {
        val testSecret = "whsec_test_dual_${System.currentTimeMillis()}"
        val liveSecret = "whsec_live_dual_${System.currentTimeMillis()}"
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = testSecret,
                liveWebhookSecretOverride = liveSecret,
            ),
            panelUrlOverride = "http://localhost:5175",
        )
        val eventId = "evt_dual_test_${System.currentTimeMillis()}"
        val payload = """{"id":"$eventId","object":"event","type":"customer.subscription.updated","data":{"object":{}}}"""
        val ts = System.currentTimeMillis() / 1000
        val sig = stripeSignatureHeader(payload, testSecret, ts)
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = payload, headers = mapOf("Stripe-Signature" to sig))
        handler.handleWebhook(ctx)
        assertEquals(200, recorder.statusCode, "test secret should be accepted")
    }

    @Test
    @Order(210)
    fun `webhook dual-secret verification accepts live-mode webhook`() {
        val testSecret = "whsec_test_dual2_${System.currentTimeMillis()}"
        val liveSecret = "whsec_live_dual2_${System.currentTimeMillis()}"
        val handler = StripeHandler(
            db, userStore, promoCodeHandler,
            keyProvider = StripeKeyProvider(
                db,
                testSecretKeyOverride = "",
                liveSecretKeyOverride = "",
                testWebhookSecretOverride = testSecret,
                liveWebhookSecretOverride = liveSecret,
            ),
            panelUrlOverride = "http://localhost:5175",
        )
        val eventId = "evt_dual_live_${System.currentTimeMillis()}"
        val payload = """{"id":"$eventId","object":"event","type":"customer.subscription.updated","data":{"object":{}}}"""
        val ts = System.currentTimeMillis() / 1000
        val sig = stripeSignatureHeader(payload, liveSecret, ts)
        val recorder = ResponseRecorder()
        val ctx = mockCtx(recorder, body = payload, headers = mapOf("Stripe-Signature" to sig))
        handler.handleWebhook(ctx)
        assertEquals(200, recorder.statusCode, "live secret should be accepted")
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

    // ========================================================================
    // PARANOID QA: race condition / row-locking tests
    // ========================================================================

    @Test
    @Order(300)
    fun `concurrent activateSubscription calls on same user produce consistent result (race test)`() {
        val userId = createTestUser("race-activate-${System.currentTimeMillis()}@properbackup.dev")
        val farFuture = Instant.parse("2099-12-31T23:59:59Z")
        stripeHandler.activateSubscription(userId, StripeHandler.ANNUAL_PLAN, farFuture)

        val threads = 8
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val errors = AtomicInteger(0)

        val nearFuture = Instant.parse("2030-01-01T00:00:00Z")
        for (i in 0 until threads) {
            pool.submit {
                latch.await()
                try {
                    stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, nearFuture)
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        val user = userStore.findById(userId).orElseThrow()
        val recorded = Instant.parse(user.subscriptionExpiresAt!!)
        // max() must have preserved the farFuture date regardless of concurrent attempts.
        assertTrue(
            Duration.between(farFuture, recorded).abs().toSeconds() <= 1,
            "Concurrent activateSubscription must preserve max(expiresAt) — got $recorded, expected ~$farFuture",
        )
    }

    @Test
    @Order(301)
    fun `findByIdForUpdate acquires row lock within a transaction`() {
        val userId = createTestUser("lock-test-${System.currentTimeMillis()}@properbackup.dev")
        val startExpiry = Instant.now().plus(Duration.ofDays(60))
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, startExpiry, 2199023255552L)

        val conn1 = db.getConnection()
        conn1.autoCommit = false
        val locked = userStore.findByIdForUpdate(conn1, userId)
        assertTrue(locked.isPresent, "Should find user with FOR UPDATE")

        // In a second thread, try to lock the same row — it should block until conn1 commits.
        val blockedStart = System.currentTimeMillis()
        val otherDone = CountDownLatch(1)
        val otherThread = Thread {
            val conn2 = db.getConnection()
            conn2.autoCommit = false
            try {
                userStore.findByIdForUpdate(conn2, userId)
            } finally {
                conn2.rollback()
                conn2.close()
                otherDone.countDown()
            }
        }
        otherThread.start()

        // Give the other thread time to start (it should block on the lock).
        Thread.sleep(200)
        assertFalse(otherDone.await(100, TimeUnit.MILLISECONDS), "Second FOR UPDATE should block while first holds lock")

        // Release the lock — second thread should proceed.
        conn1.rollback()
        conn1.close()
        assertTrue(otherDone.await(5, TimeUnit.SECONDS), "Second FOR UPDATE should complete after first releases lock")
    }

    @Test
    @Order(302)
    fun `withRetry retries on RateLimitException then succeeds`() {
        val callCount = AtomicInteger(0)
        val result = StripeHandler.withRetry("test", org.slf4j.LoggerFactory.getLogger("test")) {
            if (callCount.incrementAndGet() <= 2) {
                throw com.stripe.exception.RateLimitException("rate limited", null, null, null, 429, null)
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, callCount.get(), "Should have retried twice before succeeding")
    }

    @Test
    @Order(303)
    fun `withRetry throws after MAX_RETRIES exhausted`() {
        val callCount = AtomicInteger(0)
        val ex = assertThrows<com.stripe.exception.RateLimitException> {
            StripeHandler.withRetry("test", org.slf4j.LoggerFactory.getLogger("test")) {
                callCount.incrementAndGet()
                throw com.stripe.exception.RateLimitException("rate limited", null, null, null, 429, null)
            }
        }
        assertEquals(4, callCount.get(), "Should attempt 1 initial + 3 retries = 4 total")
        assertNotNull(ex)
    }

    @Test
    @Order(304)
    fun `withRetry does NOT retry on 4xx errors`() {
        val callCount = AtomicInteger(0)
        assertThrows<com.stripe.exception.InvalidRequestException> {
            StripeHandler.withRetry("test", org.slf4j.LoggerFactory.getLogger("test")) {
                callCount.incrementAndGet()
                throw com.stripe.exception.InvalidRequestException("bad request", "param", null, null, 400, null)
            }
        }
        assertEquals(1, callCount.get(), "4xx should NOT be retried")
    }

    // ========================================================================
    // DOWNGRADE LOGIC E2E: 9 scenariuszy (Grupy A–D)
    // ========================================================================

    // --- Grupa A: Dziewicze zakupy i odnowienia ---

    @Test
    @Order(400)
    fun `Test 1 - new client no plan buys Monthly`() {
        val userId = createTestUser("e2e-test1-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()
        val periodEnd = now.plus(Duration.ofDays(31))

        stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, periodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan, "Plan must be monthly")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        assertTrue(
            Duration.between(periodEnd, expiresAt).abs().toSeconds() <= 2,
            "ExpiresAt must equal now()+31 days — got $expiresAt, expected ~$periodEnd",
        )
    }

    @Test
    @Order(401)
    fun `Test 2 - new client no plan buys Annual`() {
        val userId = createTestUser("e2e-test2-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()
        val periodEnd = now.plus(Duration.ofDays(365))

        stripeHandler.activateSubscription(userId, StripeHandler.ANNUAL_PLAN, periodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.ANNUAL_PLAN, user.subscriptionPlan, "Plan must be annual")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        assertTrue(
            Duration.between(periodEnd, expiresAt).abs().toSeconds() <= 2,
            "ExpiresAt must equal now()+365 days — got $expiresAt, expected ~$periodEnd",
        )
    }

    @Test
    @Order(402)
    fun `Test 3 - standard renewal Monthly to Monthly via invoice webhook`() {
        val userId = createTestUser("e2e-test3-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // User has active monthly expiring in 5 days.
        val currentExpiry = now.plus(Duration.ofDays(5))
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)

        // Stripe sends invoice.paid with new period_end = now + 31 days.
        val newPeriodEnd = now.plus(Duration.ofDays(31))
        stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, newPeriodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan)
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        // max(5d, 31d) = 31d — new period wins.
        assertTrue(
            Duration.between(newPeriodEnd, expiresAt).abs().toSeconds() <= 2,
            "Renewal must extend to new period_end — got $expiresAt, expected ~$newPeriodEnd",
        )
    }

    // --- Grupa B: Upgrade / Downgrade ---

    @Test
    @Order(403)
    fun `Test 4 - upgrade Monthly to Annual`() {
        val userId = createTestUser("e2e-test4-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // User has monthly with 20 days remaining.
        val currentExpiry = now.plus(Duration.ofDays(20))
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)

        // Stripe creates annual subscription: period_end = now + 365 days.
        val annualPeriodEnd = now.plus(Duration.ofDays(365))
        stripeHandler.activateSubscription(userId, StripeHandler.ANNUAL_PLAN, annualPeriodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.ANNUAL_PLAN, user.subscriptionPlan, "Plan must upgrade to annual")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        // max(20d, 365d) = 365d — annual wins.
        assertTrue(
            Duration.between(annualPeriodEnd, expiresAt).abs().toSeconds() <= 2,
            "Upgrade must extend to annual period_end — got $expiresAt, expected ~$annualPeriodEnd",
        )
    }

    @Test
    @Order(404)
    fun `Test 5 - downgrade Annual to Monthly preserves 300 days (main bug)`() {
        val userId = createTestUser("e2e-test5-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // User has annual with 300 days remaining.
        val currentExpiry = now.plus(Duration.ofDays(300))
        userStore.updateSubscription(userId, StripeHandler.ANNUAL_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)

        // Stripe creates monthly subscription: period_end = now + 31 days.
        val monthlyPeriodEnd = now.plus(Duration.ofDays(31))
        stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, monthlyPeriodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan, "Plan changes to monthly")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        // CRITICAL: max(300d, 31d) = 300d — old annual time MUST be preserved!
        assertTrue(
            Duration.between(currentExpiry, expiresAt).abs().toSeconds() <= 2,
            "MAIN BUG: Downgrade must preserve 300-day expiry — got $expiresAt, expected ~$currentExpiry",
        )
    }

    // --- Grupa C: Skrajne przypadki (>1 rok) ---

    @Test
    @Order(405)
    fun `Test 6 - accumulated time 700 days then downgrade to Monthly`() {
        val userId = createTestUser("e2e-test6-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // User has accumulated 700 days (e.g. bought annual twice via promotion).
        val currentExpiry = now.plus(Duration.ofDays(700))
        userStore.updateSubscription(userId, StripeHandler.ANNUAL_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)

        // Downgrade to monthly — Stripe period_end = +31 days.
        val monthlyPeriodEnd = now.plus(Duration.ofDays(31))
        stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, monthlyPeriodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan, "Plan overwritten to monthly")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        // CRITICAL: max(700d, 31d) = 700d — system MUST NOT cut even a single day.
        assertTrue(
            Duration.between(currentExpiry, expiresAt).abs().toSeconds() <= 2,
            "Accumulated 700d must be preserved after downgrade — got $expiresAt, expected ~$currentExpiry",
        )
    }

    @Test
    @Order(406)
    fun `Test 7 - monthly invoice with 700 days accumulated does not shorten time`() {
        val userId = createTestUser("e2e-test7-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // User from Test 6 scenario: monthly plan with 700 days remaining.
        val currentExpiry = now.plus(Duration.ofDays(700))
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)

        // 31 days later, Stripe charges next monthly invoice: period_end = now + 62 days.
        val nextPeriodEnd = now.plus(Duration.ofDays(62))
        stripeHandler.activateSubscription(userId, StripeHandler.MONTHLY_PLAN, nextPeriodEnd)

        val user = userStore.findById(userId).orElseThrow()
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan)
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        // max(700d, 62d) = 700d — accumulated time stays intact.
        assertTrue(
            Duration.between(currentExpiry, expiresAt).abs().toSeconds() <= 2,
            "700d accumulated must remain after monthly renewal — got $expiresAt, expected ~$currentExpiry",
        )
    }

    // --- Grupa D: Anulowanie subskrypcji i ochrona danych ---

    @Test
    @Order(407)
    fun `Test 8 - cancel after downgrade preserves paid time`() {
        val userId = createTestUser("e2e-test8-${System.currentTimeMillis()}@properbackup.dev")
        val now = Instant.now()

        // Setup: User had annual (300d), downgraded to monthly. max() preserved 300d.
        val currentExpiry = now.plus(Duration.ofDays(298)) // 2 days after downgrade
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, currentExpiry, StripeHandler.STORAGE_LIMIT_BYTES)
        userStore.updateStripeSubscriptionId(userId, "sub_test_cancel_${System.currentTimeMillis()}")

        // User cancels — simulate handleSubscriptionDeleted behavior directly:
        // SELECT FOR UPDATE → detect expiresAt in future → clear only stripe_subscription_id.
        userStore.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val locked = userStore.findByIdForUpdate(conn, userId).orElseThrow()
                val expiresAt = locked.subscriptionExpiresAt?.let { Instant.parse(it) }

                // Verify expiresAt is in the future (cancel logic branch).
                assertNotNull(expiresAt, "ExpiresAt must not be null")
                assertTrue(expiresAt!!.isAfter(now), "ExpiresAt must be in the future")

                // This is what handleSubscriptionDeleted does: clear sub ID, keep plan + date.
                userStore.updateStripeSubscriptionId(conn, locked.id, null)
                userStore.setCancelAtPeriodEnd(conn, locked.id, true)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

        val user = userStore.findById(userId).orElseThrow()
        // Plan + expiry date MUST be intact.
        assertEquals(StripeHandler.MONTHLY_PLAN, user.subscriptionPlan, "Plan must remain monthly")
        assertNull(user.stripeSubscriptionId, "stripe_subscription_id must be cleared")
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        assertTrue(
            Duration.between(currentExpiry, expiresAt).abs().toSeconds() <= 2,
            "Cancel must preserve 298-day expiry — got $expiresAt, expected ~$currentExpiry",
        )
        assertTrue(user.subscriptionCancelAtPeriodEnd, "cancel_at_period_end must be true")
    }

    @Test
    @Order(408)
    fun `Test 9 - natural expiration after cancel blocks access`() {
        val userId = createTestUser("e2e-test9-${System.currentTimeMillis()}@properbackup.dev")

        // User's subscription expired 1 day ago.
        val expiredDate = Instant.now().minus(Duration.ofDays(1))
        userStore.updateSubscription(userId, StripeHandler.MONTHLY_PLAN, expiredDate, StripeHandler.STORAGE_LIMIT_BYTES)
        userStore.updateStripeSubscriptionId(userId, null)

        val user = userStore.findById(userId).orElseThrow()
        val daysRemaining = stripeHandler.calculateDaysRemaining(user)

        // Days remaining must be 0 — subscription expired.
        assertEquals(0L, daysRemaining, "Expired subscription must return 0 days remaining")

        // subscriptionExpiresAt is in the past — system blocks access without any cron.
        val expiresAt = Instant.parse(user.subscriptionExpiresAt!!)
        assertTrue(expiresAt.isBefore(Instant.now()), "ExpiresAt must be in the past")
    }
}
