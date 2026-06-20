package pl.danielniemiec.properbackup.buffer.inbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DiskGuard(
    private val watchPath: Path,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
) {

    fun hasSpace(): Boolean {
        return try {
            val free = Files.getFileStore(watchPath).usableSpace
            free >= minFreeBytes
        } catch (e: IOException) {
            true
        }
    }

    companion object {
        const val DEFAULT_MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024
    }
}
