package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.ovh.MockSwiftClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.SecureRandom

@Testcontainers
class PackPipelineHardeningTest {

    @Container
    private val postgres = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("properbackup_test")
        .withUsername("test")
        .withPassword("test")

    private lateinit var db: Database
    private lateinit var packBufferDir: Path
    private lateinit var mockDir: Path
    private lateinit var mockSwift: MockSwiftClient

    private val serverId = "test-server-hr"
    private val userId = "test-user-hr"

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "test-hr@example.com")
                ps.setString(3, "\$2a\$10\$fakehashjustfortesting000000000000000000000000000000")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "HR Test Server")
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }
        packBufferDir = Files.createTempDirectory("pb-pack-hr-")
        mockDir = Files.createTempDirectory("pb-mock-hr-")
        mockSwift = MockSwiftClient(mockDir)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        packBufferDir.toFile().deleteRecursively()
        mockDir.toFile().deleteRecursively()
    }

    @Test
    fun `force-flush stale open pack after age threshold`() {
        val packBuffer = PackBuffer(packBufferDir, db, mockSwift, 10L * 1024 * 1024, forceFlushAgeMs = 1000)

        packBuffer.addToPack(serverId, "obj-stale", randomBytes(100))

        val serverDir = packBufferDir.resolve(serverId)
        val openPack = Files.list(serverDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".open.pack") }.findFirst().orElse(null)
        }
        assertNotNull(openPack, "should have an open pack")

        Files.setLastModifiedTime(openPack!!, FileTime.fromMillis(System.currentTimeMillis() - 2000))

        packBuffer.forceFlushStalePacks()

        val remainingOpenPacks = Files.list(serverDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".open.pack") }.count()
        }
        assertEquals(0, remainingOpenPacks, "open pack should be flushed after age threshold")
    }

    @Test
    fun `young open pack is NOT force-flushed`() {
        val packBuffer = PackBuffer(packBufferDir, db, mockSwift, 10L * 1024 * 1024, forceFlushAgeMs = 60_000)

        packBuffer.addToPack(serverId, "obj-young", randomBytes(100))

        packBuffer.forceFlushStalePacks()

        val serverDir = packBufferDir.resolve(serverId)
        val openPackCount = Files.list(serverDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".open.pack") }.count()
        }
        assertEquals(1, openPackCount, "young open pack should remain")
    }

    @Test
    fun `crash recovery removes orphan tmp files`() {
        val serverDir = packBufferDir.resolve(serverId)
        Files.createDirectories(serverDir)
        Files.write(serverDir.resolve("pack_test.tmp"), ByteArray(100))

        val packBuffer = PackBuffer(packBufferDir, db, null)
        packBuffer.start()
        packBuffer.stop()

        assertFalse(Files.exists(serverDir.resolve("pack_test.tmp")))
    }

    @Test
    fun `crash recovery removes corrupt too-small pack`() {
        val serverDir = packBufferDir.resolve(serverId)
        Files.createDirectories(serverDir)
        Files.write(serverDir.resolve("pack_corrupt.pack"), ByteArray(3))

        val packBuffer = PackBuffer(packBufferDir, db, null)
        packBuffer.start()
        packBuffer.stop()

        assertFalse(Files.exists(serverDir.resolve("pack_corrupt.pack")))
    }

    @Test
    fun `crash recovery removes pack with invalid magic`() {
        val serverDir = packBufferDir.resolve(serverId)
        Files.createDirectories(serverDir)
        Files.write(serverDir.resolve("pack_bad_magic.pack"), ByteArray(10))

        val packBuffer = PackBuffer(packBufferDir, db, null)
        packBuffer.start()
        packBuffer.stop()

        assertFalse(Files.exists(serverDir.resolve("pack_bad_magic.pack")))
    }

    @Test
    fun `crash recovery preserves valid pack`() {
        val serverDir = packBufferDir.resolve(serverId)
        Files.createDirectories(serverDir)

        val validPack = buildValidPackBytes("test-obj", randomBytes(50))
        val packPath = serverDir.resolve("pack_valid.pack")
        Files.write(packPath, validPack)

        val packBuffer = PackBuffer(packBufferDir, db, null)
        packBuffer.start()
        packBuffer.stop()

        assertTrue(Files.exists(packPath), "valid pack should be preserved")
    }

    @Test
    fun `crash recovery preserves valid open pack and data is readable`() {
        val data = randomBytes(200)
        val objectName = "obj-recovered"
        val serverDir = packBufferDir.resolve(serverId)
        Files.createDirectories(serverDir)

        val validPack = buildValidPackBytes(objectName, data)
        val openPackPath = serverDir.resolve("pack_${serverId}_20260620120000_00001.open.pack")
        Files.write(openPackPath, validPack)

        val packBuffer = PackBuffer(packBufferDir, db, null)
        packBuffer.start()
        packBuffer.stop()

        assertTrue(Files.exists(openPackPath), "valid open pack should be preserved")
        val readBack = packBuffer.readObject(serverId, objectName)
        assertNotNull(readBack)
        assertArrayEquals(data, readBack)
    }

    @Test
    fun `pack format integrity after addToPack - magic and version correct`() {
        val packBuffer = PackBuffer(packBufferDir, db, null, 10L * 1024 * 1024)
        val data = randomBytes(500)
        packBuffer.addToPack(serverId, "integrity-check", data)

        val serverDir = packBufferDir.resolve(serverId)
        val packFile = Files.list(serverDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".open.pack") }.findFirst().orElse(null)
        }
        assertNotNull(packFile)

        val entries = packBuffer.readPack(packFile!!)
        assertEquals(1, entries.size)
        assertEquals("integrity-check", entries[0].objectName)
        assertArrayEquals(data, entries[0].data)
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun buildValidPackBytes(objectName: String, data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(PackBuffer.MAGIC)
        dos.writeShort(PackBuffer.VERSION)
        val nameBytes = objectName.toByteArray(Charsets.UTF_8)
        dos.writeInt(nameBytes.size)
        dos.write(nameBytes)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()
        return baos.toByteArray()
    }
}
