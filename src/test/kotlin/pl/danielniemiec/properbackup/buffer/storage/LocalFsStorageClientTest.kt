package pl.danielniemiec.properbackup.buffer.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class LocalFsStorageClientTest {

    private lateinit var tempDir: Path
    private lateinit var client: LocalFsStorageClient

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("localfs-test")
        client = LocalFsStorageClient(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `putPack writes data atomically and creates sha256 sidecar`() {
        val data = "test-pack-data-12345".toByteArray()
        val result = client.putPack("server1/pack_001.pack", data)

        assertEquals("server1/pack_001.pack", result.packName)
        assertEquals(data.size.toLong(), result.sizeBytes)
        assertTrue(result.sha256.length == 64)

        // Data file exists
        val packPath = tempDir.resolve("server1/pack_001.pack")
        assertTrue(Files.exists(packPath))
        assertArrayEquals(data, Files.readAllBytes(packPath))

        // SHA256 sidecar exists
        val sidecar = tempDir.resolve("server1/pack_001.pack.sha256")
        assertTrue(Files.exists(sidecar))
        val sidecarContent = Files.readString(sidecar).trim()
        assertTrue(sidecarContent.startsWith(result.sha256))

        // No .tmp files left
        assertFalse(Files.exists(tempDir.resolve("server1/pack_001.pack.tmp")))
    }

    @Test
    fun `putPack sha256 matches recomputed hash`() {
        val data = ByteArray(1024) { it.toByte() }
        val result = client.putPack("test.pack", data)

        val expected = LocalFsStorageClient.sha256Hex(data)
        assertEquals(expected, result.sha256)
    }

    @Test
    fun `getPack returns stored data`() {
        val data = "hello-world-pack".toByteArray()
        client.putPack("mypack.pack", data)

        val retrieved = client.getPack("mypack.pack")
        assertArrayEquals(data, retrieved)
    }

    @Test
    fun `getPack throws IOException for missing pack`() {
        assertThrows<IOException> { client.getPack("nonexistent.pack") }
    }

    @Test
    fun `exists returns true for stored pack`() {
        client.putPack("exists-test.pack", "data".toByteArray())
        assertTrue(client.exists("exists-test.pack"))
    }

    @Test
    fun `exists returns false for missing pack`() {
        assertFalse(client.exists("nope.pack"))
    }

    @Test
    fun `listPacks returns only pack files`() {
        client.putPack("a.pack", "data-a".toByteArray())
        client.putPack("b.pack", "data-b".toByteArray())
        // sidecar files (.sha256) should not appear
        val packs = client.listPacks()
        assertEquals(listOf("a.pack", "b.pack"), packs)
    }

    @Test
    fun `deletePack removes pack and sidecar`() {
        client.putPack("delete-me.pack", "data".toByteArray())
        assertTrue(client.exists("delete-me.pack"))
        assertTrue(Files.exists(tempDir.resolve("delete-me.pack.sha256")))

        client.deletePack("delete-me.pack")
        assertFalse(client.exists("delete-me.pack"))
        assertFalse(Files.exists(tempDir.resolve("delete-me.pack.sha256")))
    }

    @Test
    fun `verifyIntegrity returns true for valid pack`() {
        client.putPack("integrity.pack", "important-data".toByteArray())
        assertTrue(client.verifyIntegrity("integrity.pack"))
    }

    @Test
    fun `verifyIntegrity returns false for corrupted pack`() {
        val data = "original-data".toByteArray()
        client.putPack("corrupted.pack", data)

        // Corrupt the pack file
        Files.write(tempDir.resolve("corrupted.pack"), "tampered".toByteArray())

        assertFalse(client.verifyIntegrity("corrupted.pack"))
    }

    @Test
    fun `verifyIntegrity returns false for missing sidecar`() {
        client.putPack("nosidecar.pack", "data".toByteArray())
        Files.delete(tempDir.resolve("nosidecar.pack.sha256"))

        assertFalse(client.verifyIntegrity("nosidecar.pack"))
    }

    @Test
    fun `verifyIntegrity returns false for missing pack`() {
        assertFalse(client.verifyIntegrity("gone.pack"))
    }

    @Test
    fun `crash recovery - tmp file does not exist after successful write`() {
        client.putPack("crash-test.pack", ByteArray(4096))
        assertFalse(Files.exists(tempDir.resolve("crash-test.pack.tmp")))
    }

    @Test
    fun `large pack write and read roundtrip`() {
        val data = ByteArray(10 * 1024 * 1024) { (it % 256).toByte() } // 10MB
        val result = client.putPack("large.pack", data)

        assertEquals(data.size.toLong(), result.sizeBytes)
        assertArrayEquals(data, client.getPack("large.pack"))
        assertTrue(client.verifyIntegrity("large.pack"))
    }

    @Test
    fun `nested directory packs`() {
        client.putPack("server1/2024/01/pack_001.pack", "nested".toByteArray())
        assertTrue(client.exists("server1/2024/01/pack_001.pack"))
        assertEquals("nested", String(client.getPack("server1/2024/01/pack_001.pack")))
    }

    @Test
    fun `availableBytes returns positive value`() {
        assertTrue(client.availableBytes() > 0)
    }

    @Test
    fun `usedBytes increases after putPack`() {
        val before = client.usedBytes()
        client.putPack("usage.pack", ByteArray(1024))
        val after = client.usedBytes()
        assertTrue(after >= before + 1024)
    }
}
