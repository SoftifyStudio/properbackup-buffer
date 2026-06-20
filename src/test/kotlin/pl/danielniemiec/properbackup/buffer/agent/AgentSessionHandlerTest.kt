package pl.danielniemiec.properbackup.buffer.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pl.danielniemiec.properbackup.buffer.auth.JwtService

class AgentSessionHandlerTest {

    private val jwtService = JwtService("test-secret-for-agent-session")

    @Test
    fun `generateAgentToken produces valid token with subscription_status claim`() {
        val token = jwtService.generateAgentToken("srv-123", "user-456", "active")
        val claims = jwtService.verify(token)
        assertEquals("srv-123", claims.subject)
        assertEquals("user-456", claims["userId"])
        assertEquals("active", claims["subscription_status"])
        assertEquals("agent", claims["type"])
    }

    @Test
    fun `generateAgentToken has 5min expiry`() {
        val token = jwtService.generateAgentToken("srv-1", "user-1", "trialing")
        val claims = jwtService.verify(token)
        val ttlMs = claims.expiration.time - claims.issuedAt.time
        assertEquals(300_000L, ttlMs, "Agent token TTL must be 5 minutes")
    }

    @Test
    fun `agent token with none status is valid but indicates no subscription`() {
        val token = jwtService.generateAgentToken("srv-1", "user-1", "none")
        val claims = jwtService.verify(token)
        assertEquals("none", claims["subscription_status"])
    }
}
