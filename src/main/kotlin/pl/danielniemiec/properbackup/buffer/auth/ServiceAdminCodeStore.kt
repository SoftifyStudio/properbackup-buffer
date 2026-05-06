package pl.danielniemiec.properbackup.buffer.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import pl.danielniemiec.properbackup.buffer.db.Database
import java.sql.Timestamp
import java.time.Instant

class ServiceAdminCodeStore(private val db: Database) {

    fun ensureSeedCode(seedCode: String) {
        if (seedCode.isBlank()) return
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM service_admin_codes").use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next() && rs.getInt(1) > 0) return
                }
            }
            val hash = BCrypt.withDefaults().hashToString(12, seedCode.toCharArray())
            conn.prepareStatement(
                "INSERT INTO service_admin_codes(code_hash, label, one_time) VALUES (?, ?, FALSE)",
            ).use { ps ->
                ps.setString(1, hash)
                ps.setString(2, "seed:$seedCode")
                ps.executeUpdate()
            }
        }
    }

    fun verifyAndUse(code: String): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, code_hash, one_time, used_at, expires_at FROM service_admin_codes ORDER BY id DESC",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        val hash = rs.getString("code_hash") ?: continue
                        val oneTime = rs.getBoolean("one_time")
                        val usedAt = rs.getTimestamp("used_at")
                        val expiresAt = rs.getTimestamp("expires_at")
                        if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) continue
                        if (oneTime && usedAt != null) continue
                        val ok = BCrypt.verifyer().verify(code.toCharArray(), hash).verified
                        if (!ok) continue
                        if (oneTime) {
                            conn.prepareStatement("UPDATE service_admin_codes SET used_at = ? WHERE id = ?").use { ups ->
                                ups.setTimestamp(1, Timestamp.from(Instant.now()))
                                ups.setLong(2, id)
                                ups.executeUpdate()
                            }
                        }
                        return true
                    }
                }
            }
        }
        return false
    }
}
