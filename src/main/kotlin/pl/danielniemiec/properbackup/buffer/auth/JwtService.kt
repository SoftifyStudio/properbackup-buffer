package pl.danielniemiec.properbackup.buffer.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

class JwtService(secret: String) {

    private val key: SecretKey

    init {
        val raw = secret.toByteArray(StandardCharsets.UTF_8)
        val padded = ByteArray(32)
        System.arraycopy(raw, 0, padded, 0, minOf(raw.size, 32))
        key = Keys.hmacShaKeyFor(padded)
    }

    fun generate(userId: String, email: String, systemAdmin: Boolean): String =
        Jwts.builder()
            .subject(userId)
            .claim("email", email)
            .claim("sa", systemAdmin)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + TTL_MS))
            .signWith(key)
            .compact()

    fun generateAgentToken(serverId: String, userId: String, subscriptionStatus: String): String =
        Jwts.builder()
            .subject(serverId)
            .claim("userId", userId)
            .claim("subscription_status", subscriptionStatus)
            .claim("type", "agent")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + AGENT_TTL_MS))
            .signWith(key)
            .compact()

    fun verify(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

    companion object {
        private val TTL_MS = 30L * 24 * 60 * 60 * 1000
        private val AGENT_TTL_MS = 5L * 60 * 1000 // 5 minutes per spec [TDD-G1]
    }
}
