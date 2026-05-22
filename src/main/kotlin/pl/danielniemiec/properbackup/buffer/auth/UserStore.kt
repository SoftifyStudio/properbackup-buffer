package pl.danielniemiec.properbackup.buffer.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

class UserStore(private val db: Database) {

    data class User(
        val id: String,
        val email: String,
        val bcryptHash: String,
        val createdAt: String,
        val isSystemAdmin: Boolean,
        val trialExpiresAt: String?,
        val subscriptionPlan: String?,
        val subscriptionExpiresAt: String?,
        val storageLimitBytes: Long,
        val lastTrialNotification: String?,
        val emailVerified: Boolean = false,
        val stripeCustomerId: String? = null,
        val subscriptionCancelAtPeriodEnd: Boolean = false,
    )
    data class UserSummary(val id: String, val email: String)

    private val SELECT_COLS = "id, email, bcrypt_hash, created_at, is_system_admin, trial_expires_at, subscription_plan, subscription_expires_at, storage_limit_bytes, last_trial_notification, email_verified, stripe_customer_id, subscription_cancel_at_period_end"

    fun findByEmail(email: String): Optional<User> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $SELECT_COLS FROM users WHERE email = ?",
            ).use { ps ->
                ps.setString(1, email.lowercase())
                ps.executeQuery().use { rs ->
                    return if (rs.next()) Optional.of(map(rs)) else Optional.empty()
                }
            }
        }
    }

    fun findById(id: String): Optional<User> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $SELECT_COLS FROM users WHERE id = ?",
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) Optional.of(map(rs)) else Optional.empty()
                }
            }
        }
    }

    fun listSummaries(): List<UserSummary> {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT id, email FROM users ORDER BY email").use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<UserSummary>()
                    while (rs.next()) {
                        out.add(UserSummary(rs.getString("id"), rs.getString("email")))
                    }
                    return out
                }
            }
        }
    }

    fun register(email: String, plainPassword: String): User {
        val hash = BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray())
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val verifyToken = UUID.randomUUID().toString().replace("-", "")
        val verifyExpires = now.plusSeconds(24 * 60 * 60)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash, created_at, is_system_admin, storage_limit_bytes, email_verified, email_verify_token, email_verify_expires_at) VALUES (?, ?, ?, ?, FALSE, 2199023255552, FALSE, ?, ?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, email.lowercase())
                ps.setString(3, hash)
                ps.setTimestamp(4, Timestamp.from(now))
                ps.setString(5, verifyToken)
                ps.setTimestamp(6, Timestamp.from(verifyExpires))
                ps.executeUpdate()
            }
        }
        return User(id, email.lowercase(), hash, now.toString(), false, null, null, null, 2199023255552L, null, false, null)
    }

    fun verifyEmail(token: String): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET email_verified = TRUE, email_verify_token = NULL WHERE email_verify_token = ? AND email_verify_expires_at > NOW()",
            ).use { ps ->
                ps.setString(1, token)
                return ps.executeUpdate() > 0
            }
        }
    }

    fun getVerifyToken(userId: String): String? {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT email_verify_token FROM users WHERE id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("email_verify_token") else null
                }
            }
        }
    }

    fun regenerateVerifyToken(userId: String): String {
        val newToken = UUID.randomUUID().toString().replace("-", "")
        val expires = Instant.now().plusSeconds(24 * 60 * 60)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET email_verify_token = ?, email_verify_expires_at = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, newToken)
                ps.setTimestamp(2, Timestamp.from(expires))
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
        return newToken
    }

    fun verifyPassword(user: User, plainPassword: String): Boolean =
        BCrypt.verifyer().verify(plainPassword.toCharArray(), user.bcryptHash).verified

    fun isEmpty(): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM users").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1) == 0L
                }
            }
        }
    }

    fun updateSubscription(userId: String, plan: String, expiresAt: Instant, storageLimitBytes: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET subscription_plan = ?, subscription_expires_at = ?, storage_limit_bytes = ?, subscription_cancel_at_period_end = FALSE WHERE id = ?",
            ).use { ps ->
                ps.setString(1, plan)
                ps.setTimestamp(2, Timestamp.from(expiresAt))
                ps.setLong(3, storageLimitBytes)
                ps.setString(4, userId)
                ps.executeUpdate()
            }
        }
    }

    fun setCancelAtPeriodEnd(userId: String, cancel: Boolean) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET subscription_cancel_at_period_end = ? WHERE id = ?",
            ).use { ps ->
                ps.setBoolean(1, cancel)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun activateTrial(userId: String, trialExpiresAt: Instant) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET trial_expires_at = ? WHERE id = ? AND trial_expires_at IS NULL AND subscription_plan IS NULL",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(trialExpiresAt))
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun updateLastTrialNotification(userId: String, marker: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE users SET last_trial_notification = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, marker)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun listTrialUsersNeedingNotification(): List<User> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """SELECT $SELECT_COLS FROM users
                   WHERE subscription_plan IS NULL
                     AND trial_expires_at IS NOT NULL
                     AND trial_expires_at > NOW()
                     AND trial_expires_at <= NOW() + INTERVAL '14 days'
                   ORDER BY trial_expires_at ASC""",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<User>()
                    while (rs.next()) out.add(map(rs))
                    return out
                }
            }
        }
    }

    private fun map(rs: java.sql.ResultSet): User = User(
        rs.getString("id"),
        rs.getString("email"),
        rs.getString("bcrypt_hash"),
        rs.getTimestamp("created_at").toInstant().toString(),
        rs.getBoolean("is_system_admin"),
        rs.getTimestamp("trial_expires_at")?.toInstant()?.toString(),
        rs.getString("subscription_plan"),
        rs.getTimestamp("subscription_expires_at")?.toInstant()?.toString(),
        rs.getLong("storage_limit_bytes").let { if (rs.wasNull()) 2199023255552L else it },
        rs.getString("last_trial_notification"),
        rs.getBoolean("email_verified"),
        rs.getString("stripe_customer_id"),
        rs.getBoolean("subscription_cancel_at_period_end"),
    )
}
