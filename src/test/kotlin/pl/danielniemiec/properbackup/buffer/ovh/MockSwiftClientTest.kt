package pl.danielniemiec.properbackup.buffer.ovh

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest

class MockSwiftClientTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var client: MockSwiftClient

    @BeforeEach
    fun setUp() {
        client = MockSwiftClient(tempDir.resolve("mock-ovh"))
    }

    @Test
    fun `put and get round-trip`() {
        val data = "hello-ovh".toByteArray()
        client.put("test-obj", data)
        val result = client.get("test-obj")
        assertArrayEquals(data, result)
    }

    @Test
    fun `put and list`() {
        client.put("a/b/c.bin", ByteArray(100))
        client.put("d.bin", ByteArray(50))
        val list = client.list()
        assertEquals(2, list.size)
        assertTrue(list.any { it.contains("c.bin") })
        assertTrue(list.any { it.contains("d.bin") })
    }

    @Test
    fun `exists returns true after put, false for missing`() {
        assertFalse(client.exists("no-such"))
        client.put("present", ByteArray(10))
        assertTrue(client.exists("present"))
    }

    @Test
    fun `headObject returns metadata with correct size and etag`() {
        val data = "integrity-check".toByteArray()
        client.put("meta-test", data)
        val meta = client.headObject("meta-test")
        assertNotNull(meta)
        assertEquals(data.size.toLong(), meta!!.contentLength)
        val expectedMd5 = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedMd5, meta.etag)
    }

    @Test
    fun `headObject returns null for missing object`() {
        assertNull(client.headObject("nonexistent"))
    }

    @Test
    fun `sealed object blocks get until unseal completes`() {
        val data = "sealed-data".toByteArray()
        client.put("sealed-obj", data)
        client.simulateSeal("sealed-obj")

        assertThrows(IOException::class.java) {
            client.get("sealed-obj")
        }

        assertEquals(UnsealStatus.SEALED, client.checkUnsealStatus("sealed-obj"))
    }

    @Test
    fun `requestUnseal transitions from SEALED to UNSEALING`() {
        client.put("to-unseal", ByteArray(10))
        client.simulateSeal("to-unseal")

        assertEquals(UnsealStatus.SEALED, client.checkUnsealStatus("to-unseal"))

        val status = client.requestUnseal("to-unseal")
        assertEquals(UnsealStatus.UNSEALING, status)
        assertEquals(UnsealStatus.UNSEALING, client.checkUnsealStatus("to-unseal"))
    }

    @Test
    fun `requestUnseal returns NOT_FOUND for missing object`() {
        assertEquals(UnsealStatus.NOT_FOUND, client.requestUnseal("ghost"))
    }

    @Test
    fun `checkUnsealStatus returns UNSEALED for non-sealed object`() {
        client.put("normal-obj", ByteArray(5))
        assertEquals(UnsealStatus.UNSEALED, client.checkUnsealStatus("normal-obj"))
    }

    @Test
    fun `checkUnsealStatus returns NOT_FOUND for missing object`() {
        assertEquals(UnsealStatus.NOT_FOUND, client.checkUnsealStatus("missing"))
    }

    @Test
    fun `headObject shows x-ovh-retrieval-state for sealed object`() {
        client.put("sealed-meta", ByteArray(20))
        client.simulateSeal("sealed-meta")

        val meta = client.headObject("sealed-meta")
        assertNotNull(meta)
        assertEquals("sealed", meta!!.headers["x-ovh-retrieval-state"])
    }

    @Test
    fun `headObject shows unsealing state after requestUnseal`() {
        client.put("unsealing-meta", ByteArray(20))
        client.simulateSeal("unsealing-meta")
        client.requestUnseal("unsealing-meta")

        val meta = client.headObject("unsealing-meta")
        assertNotNull(meta)
        assertEquals("unsealing", meta!!.headers["x-ovh-retrieval-state"])
    }

    @Test
    fun `delete removes object and clears seal state`() {
        client.put("del-obj", ByteArray(10))
        client.simulateSeal("del-obj")
        client.delete("del-obj")

        assertFalse(client.exists("del-obj"))
        assertEquals(UnsealStatus.NOT_FOUND, client.checkUnsealStatus("del-obj"))
    }

    @Test
    fun `simulated error rate causes IOException`() {
        val errorClient = MockSwiftClient(tempDir.resolve("mock-error"), errorRatePercent = 100)
        assertThrows(IOException::class.java) {
            errorClient.put("fail-obj", ByteArray(10))
        }
    }

    @Test
    fun `no errors with zero error rate`() {
        val safeClient = MockSwiftClient(tempDir.resolve("mock-safe"), errorRatePercent = 0)
        assertDoesNotThrow {
            safeClient.put("safe-obj", ByteArray(10))
        }
    }

    @Test
    fun `atomic write - tmp file not visible in list`() {
        client.put("visible.bin", ByteArray(50))
        val list = client.list()
        assertTrue(list.none { it.endsWith(".tmp") })
    }
}
