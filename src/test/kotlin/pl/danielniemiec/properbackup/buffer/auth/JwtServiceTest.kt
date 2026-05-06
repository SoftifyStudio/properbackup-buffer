package pl.danielniemiec.properbackup.buffer.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JwtServiceTest {

    @Test
    fun generateAndVerifyRoundTrip() {
        val jwt = JwtService("unit-test-secret-at-least-32-bytes-long!!")
        val token = jwt.generate("user-1", "a@b.c", systemAdmin = false)
        val claims = jwt.verify(token)
        assertEquals("user-1", claims.subject)
        assertEquals("a@b.c", claims["email"])
        assertFalse(claims["sa"] as Boolean)
    }

    @Test
    fun systemAdminClaimSurvives() {
        val jwt = JwtService("another-32-byte-secret-for-jwt-test")
        val token = jwt.generate("admin", "root@x.y", systemAdmin = true)
        val claims = jwt.verify(token)
        assertTrue(claims["sa"] as Boolean)
    }
}
