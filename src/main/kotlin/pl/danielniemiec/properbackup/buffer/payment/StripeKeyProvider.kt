package pl.danielniemiec.properbackup.buffer.payment

import com.stripe.net.RequestOptions
import org.slf4j.LoggerFactory
import pl.danielniemiec.properbackup.buffer.db.Database

/**
 * Isolated provider for Stripe API keys. All key selection and storage lives here —
 * business code never touches raw key strings.
 *
 * Each user has a `stripe_test_mode` flag in the DB (default TRUE). When the flag is
 * TRUE the provider returns test keys; when FALSE it returns live keys.
 *
 * Invariants:
 *   - Actual key values are NEVER logged, serialised, or exposed to callers other
 *     than via [RequestOptions] or the dedicated [secretKeyFor] accessor.
 *   - The provider is the SINGLE source of truth for which key set to use.
 *   - Live keys default to the same env-var values as test keys so the operator can
 *     flip a user to "live" before configuring separate production credentials.
 */
class StripeKeyProvider(
    private val db: Database,
    testSecretKeyOverride: String? = null,
    liveSecretKeyOverride: String? = null,
    testPublicKeyOverride: String? = null,
    livePublicKeyOverride: String? = null,
    testWebhookSecretOverride: String? = null,
    liveWebhookSecretOverride: String? = null,
) {
    private val log = LoggerFactory.getLogger(StripeKeyProvider::class.java)

    // ── Key loading ────────────────────────────────────────────────────────
    // Test keys
    private val testSecretKey: String =
        testSecretKeyOverride
            ?: System.getenv("STRIPE_TEST_SECRET_KEY")
            ?: System.getenv("STRIPE_SECRET_KEY")
            ?: ""
    private val testPublicKey: String =
        testPublicKeyOverride
            ?: System.getenv("STRIPE_TEST_PUBLIC_KEY")
            ?: System.getenv("STRIPE_PUBLIC_KEY")
            ?: ""
    private val testWebhookSecret: String =
        testWebhookSecretOverride
            ?: System.getenv("STRIPE_TEST_WEBHOOK_SECRET")
            ?: System.getenv("STRIPE_WEBHOOK_SECRET")
            ?: ""

    // Live keys — default to same as test keys so flipping the DB flag works
    // immediately without requiring separate env-var configuration.
    private val liveSecretKey: String =
        liveSecretKeyOverride
            ?: System.getenv("STRIPE_LIVE_SECRET_KEY")
            ?: testSecretKey
    private val livePublicKey: String =
        livePublicKeyOverride
            ?: System.getenv("STRIPE_LIVE_PUBLIC_KEY")
            ?: testPublicKey
    private val liveWebhookSecret: String =
        liveWebhookSecretOverride
            ?: System.getenv("STRIPE_LIVE_WEBHOOK_SECRET")
            ?: testWebhookSecret

    init {
        // Deliberately log only the mode and key prefix — NEVER the full key.
        val testKeyPrefix = if (testSecretKey.length >= 8) testSecretKey.take(8) + "…" else "(empty)"
        val liveKeyPrefix = if (liveSecretKey.length >= 8) liveSecretKey.take(8) + "…" else "(empty)"
        log.info("StripeKeyProvider initialised: testKey={} liveKey={}", testKeyPrefix, liveKeyPrefix)

        // Fail-loud diagnostics so operators discover misconfiguration before money moves.
        if (liveSecretKey.isNotBlank() && liveSecretKey == testSecretKey) {
            log.warn(
                "⚠️  STRIPE LIVE KEYS NOT CONFIGURED — live secret key falls back to test secret key. " +
                    "If any user has stripe_test_mode=FALSE their billing will hit Stripe TEST dashboard " +
                    "(no real revenue). Set STRIPE_LIVE_SECRET_KEY / STRIPE_LIVE_PUBLIC_KEY / " +
                    "STRIPE_LIVE_WEBHOOK_SECRET before promoting users to live.",
            )
        }
        if (testSecretKey.isNotBlank() && testSecretKey.startsWith("sk_live_")) {
            log.error("CRITICAL: STRIPE_TEST_SECRET_KEY has sk_live_ prefix — test/live keys appear swapped!")
        }
        if (liveSecretKey.isNotBlank() && liveSecretKey != testSecretKey && liveSecretKey.startsWith("sk_test_")) {
            log.error("CRITICAL: STRIPE_LIVE_SECRET_KEY has sk_test_ prefix — live mode would still hit test Stripe!")
        }
    }

    // ── Per-user mode flag ────────────────────────────────────────────────

    fun isTestMode(userId: String): Boolean {
        return try {
            db.getConnection().use { conn ->
                conn.prepareStatement("SELECT stripe_test_mode FROM users WHERE id = ?").use { ps ->
                    ps.setString(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getBoolean("stripe_test_mode") else true
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to read stripe_test_mode for userId={}: {} — defaulting to test", userId, e.message)
            true
        }
    }

    fun setTestMode(userId: String, testMode: Boolean) {
        db.getConnection().use { conn ->
            conn.prepareStatement("UPDATE users SET stripe_test_mode = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, testMode)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
        log.info("stripe_test_mode updated: userId={} testMode={}", userId, testMode)
    }

    // ── Key accessors ─────────────────────────────────────────────────────

    fun secretKeyFor(userId: String): String = secretKeyForMode(isTestMode(userId))

    fun secretKeyForMode(testMode: Boolean): String =
        if (testMode) testSecretKey else liveSecretKey

    fun publicKeyFor(userId: String): String = publicKeyForMode(isTestMode(userId))

    fun publicKeyForMode(testMode: Boolean): String =
        if (testMode) testPublicKey else livePublicKey

    fun webhookSecretForMode(testMode: Boolean): String =
        if (testMode) testWebhookSecret else liveWebhookSecret

    fun requestOptionsFor(userId: String): RequestOptions =
        requestOptionsForMode(isTestMode(userId))

    fun requestOptionsForMode(testMode: Boolean): RequestOptions =
        RequestOptions.builder()
            .setApiKey(secretKeyForMode(testMode))
            .build()

    // ── Webhook helpers ───────────────────────────────────────────────────

    /**
     * Returns the list of (testMode, webhookSecret) pairs to try when verifying
     * a webhook signature. Test mode is attempted first because the majority of
     * traffic during development is test-mode.
     */
    fun webhookSecretCandidates(): List<Pair<Boolean, String>> {
        val candidates = mutableListOf<Pair<Boolean, String>>()
        if (testWebhookSecret.isNotBlank()) candidates.add(true to testWebhookSecret)
        if (liveWebhookSecret.isNotBlank() && liveWebhookSecret != testWebhookSecret) {
            candidates.add(false to liveWebhookSecret)
        }
        return candidates
    }

    // ── Convenience ───────────────────────────────────────────────────────

    fun hasAnyKey(): Boolean = testSecretKey.isNotBlank() || liveSecretKey.isNotBlank()

    fun modeLabel(userId: String): String = if (isTestMode(userId)) "test" else "live"

    fun modeLabelForMode(testMode: Boolean): String = if (testMode) "test" else "live"
}
