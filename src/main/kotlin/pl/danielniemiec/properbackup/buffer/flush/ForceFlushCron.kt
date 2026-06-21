package pl.danielniemiec.properbackup.buffer.flush

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HR-7: Force-flush packs that have been open for > 24 hours.
 * Runs every hour, scans for .open.pack files older than [maxAgeHours].
 * Force-flushes them even if they haven't reached 900MB.
 */
class ForceFlushCron(
    private val packBuffer: PackBuffer,
    private val bufferDir: Path,
    private val maxAgeHours: Long = 24L,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "force-flush-cron").apply { isDaemon = true }
    }
    private val forceFlushCount = AtomicInteger(0)

    fun start() {
        scheduler.scheduleAtFixedRate({ checkAndForceFlush() }, 1, 1, TimeUnit.HOURS)
        println("[force-flush] ForceFlushCron started (check every 1h, max age=${maxAgeHours}h)")
    }

    fun stop() {
        scheduler.shutdown()
    }

    fun checkAndForceFlush() {
        try {
            if (!Files.exists(bufferDir)) return
            val now = Instant.now()
            val maxAge = Duration.ofHours(maxAgeHours)

            Files.list(bufferDir).use { serverDirs ->
                for (serverDir in serverDirs.filter { Files.isDirectory(it) }.toList()) {
                    Files.list(serverDir).use { packs ->
                        for (packFile in packs.filter { it.toString().endsWith(".open.pack") }.toList()) {
                            val attrs = Files.readAttributes(packFile, BasicFileAttributes::class.java)
                            val age = Duration.between(attrs.creationTime().toInstant(), now)
                            if (age > maxAge) {
                                val serverId = serverDir.fileName.toString()
                                val sizeBytes = Files.size(packFile)
                                println("[force-flush] Force-flushing stale pack ${packFile.fileName} " +
                                    "(age=${age.toHours()}h, size=${sizeBytes / 1024}KB, reason=age_${maxAgeHours}h)")
                                forceFlushCount.incrementAndGet()
                                packBuffer.flushAll()
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[force-flush] error: ${e.message}")
        }
    }

    fun forceFlushCount(): Int = forceFlushCount.get()
}
