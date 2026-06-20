package pl.danielniemiec.properbackup.buffer.inbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DiskGuard(private val watchPath: Path) {

    fun hasSpace(): Boolean {
        return try {
            val store = Files.getFileStore(watchPath)
            val free = store.usableSpace
            val total = store.totalSpace
            if (total <= 0) return false

            val usedPercent = ((total - free).toDouble() / total * 100).toInt()
            if (usedPercent >= DISK_FULL_THRESHOLD_PERCENT) {
                System.err.println(
                    "[DiskGuard] soft block: disk ${usedPercent}% full (threshold=${DISK_FULL_THRESHOLD_PERCENT}%)"
                )
                return false
            }
            free >= MIN_FREE_BYTES
        } catch (e: IOException) {
            System.err.println("[DiskGuard] fail-safe: cannot read disk stats — blocking (${e.message})")
            false
        }
    }

    companion object {
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024
        const val DISK_FULL_THRESHOLD_PERCENT = 80
    }
}
