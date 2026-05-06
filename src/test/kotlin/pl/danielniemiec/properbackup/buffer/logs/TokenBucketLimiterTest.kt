package pl.danielniemiec.properbackup.buffer.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketLimiterTest {

    @Test
    fun shouldDropWhenCapacityExceeded() {
        val limiter = TokenBucketLimiter(capacity = 2.0, refillPerSecond = 0.0)
        assertTrue(limiter.tryAcquire("m1|Agent"))
        assertTrue(limiter.tryAcquire("m1|Agent"))
        assertFalse(limiter.tryAcquire("m1|Agent"))
        assertEquals(1, limiter.consumeDropped("m1|Agent"))
        assertEquals(0, limiter.consumeDropped("m1|Agent"))
    }
}
