package pl.danielniemiec.properbackup.buffer.ovh

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DevSafetyGuardTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var mock: MockSwiftClient
    private lateinit var guard: DevSafetyGuard

    @BeforeEach
    fun setUp() {
        mock = MockSwiftClient(tempDir.resolve("mock-ovh"))
        guard = DevSafetyGuard(mock)
    }

    @Test
    fun `put delegates to underlying client`() {
        val data = ByteArray(100)
        guard.put("new-obj", data)
        assertTrue(guard.exists("new-obj"))
    }

    @Test
    fun `delete throws SafetyException in safe mode`() {
        mock.put("del-target", ByteArray(10))
        assertThrows(SafetyException::class.java) {
            guard.delete("del-target")
        }
    }

    @Test
    fun `overwrite throws SafetyException in safe mode`() {
        guard.put("overwrite-target", ByteArray(10))
        assertThrows(SafetyException::class.java) {
            guard.put("overwrite-target", ByteArray(20))
        }
    }

    @Test
    fun `headObject delegates to underlying client`() {
        guard.put("head-target", ByteArray(42))
        val meta = guard.headObject("head-target")
        assertNotNull(meta)
        assertEquals(42L, meta!!.contentLength)
    }

    @Test
    fun `headObject returns null for missing object`() {
        assertNull(guard.headObject("missing"))
    }

    @Test
    fun `requestUnseal delegates to underlying client`() {
        guard.put("unseal-target", ByteArray(10))
        mock.simulateSeal("unseal-target")
        val status = guard.requestUnseal("unseal-target")
        assertEquals(UnsealStatus.UNSEALING, status)
    }

    @Test
    fun `checkUnsealStatus delegates to underlying client`() {
        guard.put("status-target", ByteArray(10))
        assertEquals(UnsealStatus.UNSEALED, guard.checkUnsealStatus("status-target"))
    }

    @Test
    fun `isSafeMode returns true by default`() {
        assertTrue(guard.isSafeMode())
    }
}
