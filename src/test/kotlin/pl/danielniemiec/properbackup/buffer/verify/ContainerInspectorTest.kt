package pl.danielniemiec.properbackup.buffer.verify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Hand-builds ProperCrypto-format containers (no decryption needed) to prove the
 * structural check accepts well-formed objects and rejects truncated/garbled ones.
 */
class ContainerInspectorTest {

    @Test
    fun validContainerWithTwoFramesPasses() {
        val data = container(frame(40), frame(120))
        val r = ContainerInspector.inspect(data)
        assertTrue(r.ok, r.detail)
        assertEquals(2, r.frameCount)
    }

    @Test
    fun headerOnlyHasNoFramesAndFails() {
        val r = ContainerInspector.inspect(header())
        assertFalse(r.ok)
    }

    @Test
    fun truncatedHeaderFails() {
        val r = ContainerInspector.inspect(ByteArray(20))
        assertFalse(r.ok)
    }

    @Test
    fun badMagicFails() {
        val data = container(frame(40))
        data[0] = 'X'.code.toByte()
        assertFalse(ContainerInspector.inspect(data).ok)
    }

    @Test
    fun unsupportedVersionFails() {
        val data = container(frame(40))
        data[4] = 0x02
        assertFalse(ContainerInspector.inspect(data).ok)
    }

    @Test
    fun truncatedFrameBodyFails() {
        val data = container(frame(120))
        // Drop the last 30 bytes so the declared frame length overruns the data.
        val truncated = data.copyOf(data.size - 30)
        assertFalse(ContainerInspector.inspect(truncated).ok)
    }

    @Test
    fun frameLengthTooSmallFails() {
        // frameLen below the 28-byte minimum (nonce + GCM tag).
        val data = container(frame(10))
        assertFalse(ContainerInspector.inspect(data).ok)
    }

    @Test
    fun trailingGarbageFails() {
        val data = container(frame(40)) + byteArrayOf(1, 2, 3)
        assertFalse(ContainerInspector.inspect(data).ok)
    }

    // --- helpers: mirror the on-wire format from HeaderCodec / ProperCrypto ---

    private fun header(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x50, 0x52, 0x4F, 0x50)) // "PROP"
        out.write(0x01) // version
        out.write(ByteArray(16)) // salt
        out.write(ByteArray(12)) // canary nonce
        out.write(ByteArray(16)) // canary tag
        return out.toByteArray()
    }

    /** A frame: int32 frameLen (BE) + frameLen bytes (12 nonce + ciphertext+tag). */
    private fun frame(frameLen: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((frameLen ushr 24) and 0xFF)
        out.write((frameLen ushr 16) and 0xFF)
        out.write((frameLen ushr 8) and 0xFF)
        out.write(frameLen and 0xFF)
        out.write(ByteArray(frameLen))
        return out.toByteArray()
    }

    private fun container(vararg frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header())
        for (f in frames) out.write(f)
        return out.toByteArray()
    }
}
