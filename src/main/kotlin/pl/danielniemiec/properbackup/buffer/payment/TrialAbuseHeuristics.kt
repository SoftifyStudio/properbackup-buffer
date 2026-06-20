package pl.danielniemiec.properbackup.buffer.payment

import pl.danielniemiec.properbackup.buffer.db.Database

/**
 * Trial abuse detection per master-tdd-plan.md §GRUPA E [TDD-E1].
 *
 * Layer 1 (mandatory): Card fingerprint check.
 * If same card fingerprint was used on another account that has/had a trial,
 * the new trial is shortened to NOW (instant charge).
 *
 * Layers 2-6 (optional/future): rate limiting, disposable email, velocity,
 * device fingerprint, geo IP — stored as abuse_score.
 */
class TrialAbuseHeuristics(private val db: Database) {

    data class AbuseResult(
        val isAbuse: Boolean,
        val score: Int,
        val reasons: List<String>,
    )

    /**
     * Check if this card fingerprint was already used on another trial.
     * Called after checkout.session.completed webhook processes fingerprint.
     */
    fun checkCardFingerprint(userId: String, fingerprint: String?): AbuseResult {
        if (fingerprint.isNullOrBlank()) return AbuseResult(false, 0, emptyList())

        val reasons = mutableListOf<String>()
        var score = 0

        val matchCount = countOtherUsersWithFingerprint(userId, fingerprint)
        if (matchCount > 0) {
            score += 70
            reasons.add("fingerprint_match:$matchCount accounts")
        }

        return AbuseResult(score >= 70, score, reasons)
    }

    /**
     * Update abuse_blocked and abuse_score in DB for the given user.
     */
    fun markAbuse(userId: String, score: Int) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET abuse_blocked = TRUE, abuse_score = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setInt(1, score)
                stmt.setString(2, userId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Store the card fingerprint for this user.
     */
    fun storeFingerprint(userId: String, fingerprint: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET stripe_card_fingerprint = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, fingerprint)
                stmt.setString(2, userId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Admin endpoint: clear abuse block for a user.
     */
    fun clearAbuseBlock(userId: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET abuse_blocked = FALSE, abuse_score = 0 WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, userId)
                stmt.executeUpdate()
            }
        }
    }

    private fun countOtherUsersWithFingerprint(userId: String, fingerprint: String): Int {
        return db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT COUNT(*) FROM users 
                   WHERE stripe_card_fingerprint = ? 
                   AND id != ? 
                   AND (ever_subscribed = TRUE OR trial_expires_at IS NOT NULL)"""
            ).use { stmt ->
                stmt.setString(1, fingerprint)
                stmt.setString(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }
}
