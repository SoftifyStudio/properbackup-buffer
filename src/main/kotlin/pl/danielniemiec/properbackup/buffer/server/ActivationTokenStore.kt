package pl.danielniemiec.properbackup.buffer.server

import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ActivationTokenStore(private val db: Database) {

    data class ActivationToken(val token: String, val userId: String, val expiresAt: String, val used: Boolean)
    data class ActivationTokenPending(val token: String, val expiresAt: String, val createdAt: String, val ownerEmail: String)

    companion object {
        private val TTL_MS = 24L * 60 * 60 * 1000
    }

    fun generate(userId: String): String {
        val token = UUID.randomUUID().toString().replace("-", "").substring(0, 16).uppercase()
        val expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + TTL_MS)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO activation_tokens (token, user_id, expires_at) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, token)
                ps.setString(2, userId)
                ps.setTimestamp(3, Timestamp.from(expiresAt))
                ps.executeUpdate()
            }
        }
        return token
    }

    fun findValid(conn: Connection, token: String): Optional<ActivationToken> {
        conn.prepareStatement(
            "SELECT token, user_id, expires_at, used FROM activation_tokens " +
                "WHERE token = ? AND used = false AND expires_at > NOW()",
        ).use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    Optional.of(
                        ActivationToken(
                            rs.getString("token"),
                            rs.getString("user_id"),
                            rs.getTimestamp("expires_at").toInstant().toString(),
                            rs.getBoolean("used"),
                        ),
                    )
                } else {
                    Optional.empty()
                }
            }
        }
    }

    fun markUsed(conn: Connection, token: String) {
        conn.prepareStatement("UPDATE activation_tokens SET used = true WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeUpdate()
        }
    }

    fun listPendingForUser(userId: String): List<ActivationTokenPending> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT t.token, t.expires_at, t.created_at, u.email AS owner_email " +
                    "FROM activation_tokens t " +
                    "JOIN users u ON u.id = t.user_id " +
                    "WHERE t.user_id = ? AND t.used = false AND t.expires_at > NOW() " +
                    "ORDER BY t.created_at DESC",
            ).use { ps ->
                ps.setString(1, userId)
                return readPendingRows(ps)
            }
        }
    }

    fun listAllPending(): List<ActivationTokenPending> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT t.token, t.expires_at, t.created_at, u.email AS owner_email " +
                    "FROM activation_tokens t " +
                    "JOIN users u ON u.id = t.user_id " +
                    "WHERE t.used = false AND t.expires_at > NOW() " +
                    "ORDER BY t.created_at DESC",
            ).use { ps ->
                return readPendingRows(ps)
            }
        }
    }

    private fun readPendingRows(ps: PreparedStatement): List<ActivationTokenPending> {
        val out = ArrayList<ActivationTokenPending>()
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    ActivationTokenPending(
                        rs.getString("token"),
                        rs.getTimestamp("expires_at").toInstant().toString(),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getString("owner_email"),
                    ),
                )
            }
        }
        return out
    }
}
