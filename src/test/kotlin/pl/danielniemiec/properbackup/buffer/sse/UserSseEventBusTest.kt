package pl.danielniemiec.properbackup.buffer.sse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UserSseEventBusTest {

    @Test
    fun `connectionCount is zero for unknown user`() {
        val bus = UserSseEventBus()
        assertEquals(0, bus.connectionCount("user-1", "subscription"))
    }

    @Test
    fun `publish to empty bus does not throw`() {
        val bus = UserSseEventBus()
        assertDoesNotThrow {
            bus.publish("user-1", "subscription", mapOf("type" to "subscription_activated"))
        }
    }

    @Test
    fun `publish to different user does not throw`() {
        val bus = UserSseEventBus()
        assertDoesNotThrow {
            bus.publish("user-1", "subscription", mapOf("type" to "test"))
            bus.publish("user-2", "other", mapOf("type" to "test"))
        }
    }
}
