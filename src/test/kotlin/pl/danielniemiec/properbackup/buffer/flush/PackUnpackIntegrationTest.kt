package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.danielniemiec.properbackup.buffer.db.Database
import pl.danielniemiec.properbackup.buffer.store.LocalObjectStore
import pl.danielniemiec.properbackup.buffer.store.ObjectReadFacade
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Integration test: pack multiple files into a pack → read a single one back
 * via ObjectReadFacade.getMerged() → verify bytes are identical.
 *
 * Uses Testcontainers + real PostgreSQL (NOT mock / NOT H2).
 * Covers the unpack-on-read path through PackBuffer.readObject().
 */
@Testcontainers
class PackUnpackIntegrationTest {

    @Container
    private val postgres = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("properbackup_test")
        .withUsername("test")
        .withPassword("test")

    private lateinit var db: Database
    private lateinit var storageDir: Path
    private lateinit var packBufferDir: Path
    private lateinit var objectStore: LocalObjectStore
    private lateinit var packBuffer: PackBuffer
    private lateinit var facade: ObjectReadFacade

    private val serverId = "test-server-001"
    private val userId = "test-user-001"

    @BeforeEach
    fun setUp() {
        db = Database(postgres.jdbcUrl, postgres.username, postgres.password)

        // Insert prerequisite rows so FK constraints are satisfied
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, bcrypt_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, "test@example.com")
                ps.setString(3, "\$2a\$10\$fakehashjustfortesting000000000000000000000000000000")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO servers (id, name, user_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, serverId)
                ps.setString(2, "Test Server")
                ps.setString(3, userId)
                ps.executeUpdate()
            }
        }

        storageDir = Files.createTempDirectory("pb-store-")
        packBufferDir = Files.createTempDirectory("pb-pack-")

        objectStore = LocalObjectStore(storageDir)
        packBuffer = PackBuffer(packBufferDir, db, swiftRemote = null, storageClient = null, maxPackBytes = 10L * 1024 * 1024) // 10 MB pack limit for test
        facade = ObjectReadFacade(objectStore, null, packBuffer)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        storageDir.toFile().deleteRecursively()
        packBufferDir.toFile().deleteRecursively()
    }

    @Test
    fun `read single object from pack - bytes identical to original`() {
        val fileA = randomBytes(1024)       // 1 KB
        val fileB = randomBytes(2048)       // 2 KB
        val fileC = randomBytes(512)        // 0.5 KB

        val nameA = "$userId-240101-pathA-A-abcd.enc"
        val nameB = "$userId-240101-pathB-A-ef01.enc"
        val nameC = "$userId-240101-pathC-A-2345.enc"

        // Pack all three objects
        packBuffer.addToPack(serverId, nameA, fileA)
        packBuffer.addToPack(serverId, nameB, fileB)
        packBuffer.addToPack(serverId, nameC, fileC)

        // Simulate ChunkSealer behavior: individual objects NOT in local store
        // (they were deleted after being added to pack)

        // Read each back via ObjectReadFacade — must find them in pack
        val readA = facade.getMerged(nameA)
        val readB = facade.getMerged(nameB)
        val readC = facade.getMerged(nameC)

        assertArrayEquals(fileA, readA, "fileA bytes mismatch after unpack")
        assertArrayEquals(fileB, readB, "fileB bytes mismatch after unpack")
        assertArrayEquals(fileC, readC, "fileC bytes mismatch after unpack")
    }

    @Test
    fun `read encrypted object from pack - decrypts correctly`() {
        val plaintext = "ProperBackup restore test — original file content ąęść".toByteArray()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }    // AES-256
        val encrypted = encryptAesGcm(plaintext, key)

        val objectName = "$userId-240101-pathX-A-ff00.enc"
        packBuffer.addToPack(serverId, objectName, encrypted)

        // Read back from pack via facade
        val fromPack = facade.getMerged(objectName)
        assertArrayEquals(encrypted, fromPack, "Encrypted bytes mismatch from pack")

        // Decrypt and verify plaintext
        val decrypted = decryptAesGcm(fromPack, key)
        assertArrayEquals(plaintext, decrypted, "Decrypted content mismatch")
    }

    @Test
    fun `object in local store is returned directly without hitting pack`() {
        val data = randomBytes(256)
        val objectName = "$userId-240101-pathL-A-1111.enc"

        // Put object in local store (simulates pre-pack state)
        objectStore.put(objectName, data)

        // Also put different data in pack (should not be returned)
        val differentData = randomBytes(256)
        packBuffer.addToPack(serverId, objectName, differentData)

        // Local store takes priority
        val result = facade.getMerged(objectName)
        assertArrayEquals(data, result, "Local store should take priority over pack")
    }

    @Test
    fun `missing object throws IOException with clear message`() {
        val ex = assertThrows(IOException::class.java) {
            facade.getMerged("nonexistent-object.enc")
        }
        assertNotNull(ex.message)
        assert(ex.message!!.contains("not found")) { "Error message should indicate object not found: ${ex.message}" }
    }

    @Test
    fun `read from pack across multiple servers`() {
        val data1 = randomBytes(1024)
        val data2 = randomBytes(2048)
        val name1 = "$userId-240101-pathM-A-aaaa.enc"
        val name2 = "$userId-240101-pathN-A-bbbb.enc"

        packBuffer.addToPack("server-alpha", name1, data1)
        packBuffer.addToPack("server-beta", name2, data2)

        // Facade should find objects across different server pack dirs
        assertArrayEquals(data1, facade.getMerged(name1))
        assertArrayEquals(data2, facade.getMerged(name2))
    }

    // --- helpers ---

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun encryptAesGcm(plaintext: ByteArray, key: ByteArray): ByteArray {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return salt + nonce + ciphertext
    }

    private fun decryptAesGcm(encrypted: ByteArray, key: ByteArray): ByteArray {
        val nonce = encrypted.sliceArray(32 until 44)
        val ciphertext = encrypted.sliceArray(44 until encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }
}
