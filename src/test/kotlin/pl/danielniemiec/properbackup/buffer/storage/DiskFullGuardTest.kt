package pl.danielniemiec.properbackup.buffer.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DiskFullGuardTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("diskguard-test")
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `checkDisk returns disk status`() {
        val guard = DiskFullGuard(tempDir)
        val status = guard.checkDisk()
        assertTrue(status.totalBytes > 0)
        assertTrue(status.usableBytes > 0)
        assertTrue(status.usedPercent >= 0.0)
        assertTrue(status.usedPercent < 100.0)
    }

    @Test
    fun `canAcceptUpload returns true when disk has space`() {
        val guard = DiskFullGuard(tempDir, thresholdPercent = 99.9)
        assertTrue(guard.canAcceptUpload(1024))
    }

    @Test
    fun `canAcceptUpload returns false when threshold exceeded`() {
        // threshold 0% means any usage blocks
        val guard = DiskFullGuard(tempDir, thresholdPercent = 0.001)
        assertFalse(guard.canAcceptUpload())
    }
}
