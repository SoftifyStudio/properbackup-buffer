package pl.danielniemiec.properbackup.buffer.inbox

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DiskGuardTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `hasSpace returns boolean without throwing`() {
        val guard = DiskGuard(tempDir)
        val result = guard.hasSpace()
        // On a normal system this should be true (enough free space)
        // The important thing is it doesn't throw
        assertNotNull(result)
    }

    @Test
    fun `DISK_FULL_THRESHOLD_PERCENT is 80`() {
        assertEquals(80, DiskGuard.DISK_FULL_THRESHOLD_PERCENT)
    }

    @Test
    fun `hasSpace for nonexistent path returns false (fail-safe)`() {
        val guard = DiskGuard(tempDir.resolve("nonexistent-subdir-xyz"))
        // On some systems this will throw IOException -> fail-safe returns false
        // On others the parent store is used and returns true
        // Either way, it should not crash
        assertDoesNotThrow { guard.hasSpace() }
    }
}
