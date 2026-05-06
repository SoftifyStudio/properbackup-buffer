package pl.danielniemiec.properbackup.buffer.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

class UserStore(private val db: Database) {

    data class User(val id: String, val email: String, val bcryptHash: String, val createdAt: String, val isSystemAdmin: Boolean)
    data class UserSummary(val id: String, val email: String)

    fun findByEmail(email: String): Optional<User> {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, email, bcrypt_hash, created_at, is_system_admin FROM users WHERE email = ?",
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
                "SELECT id, email, bcrypt_hash, created_at, is_system_admin FROM users WHERE id = ?",
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
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash, created_at, is_system_admin) VALUES (?, ?, ?, ?, FALSE)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, email.lowercase())
                ps.setString(3, hash)
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
        return User(id, email.lowercase(), hash, Instant.now().toString(), false)
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

    private fun map(rs: java.sql.ResultSet): User = User(
        rs.getString("id"),
        rs.getString("email"),
        rs.getString("bcrypt_hash"),
        rs.getTimestamp("created_at").toInstant().toString(),
        rs.getBoolean("is_system_admin"),
    )
}
