package pl.danielniemiec.properbackup.buffer.logs

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class TokenBucketLimiter(
    private val capacity: Double = 300.0,
    private val refillPerSecond: Double = 50.0,
) {
    private data class Bucket(var tokens: Double, var lastSec: Long, var dropped: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String): Boolean {
        val now = Instant.now().epochSecond
        val b = buckets.computeIfAbsent(key) { Bucket(capacity, now, 0) }
        synchronized(b) {
            val elapsed = now - b.lastSec
            if (elapsed > 0) {
                b.tokens = min(capacity, b.tokens + elapsed * refillPerSecond)
                b.lastSec = now
            }
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0
                return true
            }
            b.dropped++
            return false
        }
    }

    fun consumeDropped(key: String): Int {
        val b = buckets[key] ?: return 0
        synchronized(b) {
            val d = b.dropped
            b.dropped = 0
            return d
        }
    }
}
