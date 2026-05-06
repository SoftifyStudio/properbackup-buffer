package pl.danielniemiec.properbackup.buffer.inbox

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DiskGuard(private val watchPath: Path) {

    fun hasSpace(): Boolean {
        return try {
            val free = Files.getFileStore(watchPath).usableSpace
            free >= MIN_FREE_BYTES
        } catch (e: IOException) {
            true
        }
    }

    companion object {
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024
    }
}
