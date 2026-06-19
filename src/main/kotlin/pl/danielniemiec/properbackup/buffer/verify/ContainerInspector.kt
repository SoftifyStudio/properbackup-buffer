package pl.danielniemiec.properbackup.buffer.verify

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException

/**
 * Structural validation of a ProperCrypto-sealed container WITHOUT decrypting.
 *
 * The buffer is zero-knowledge — it never holds the encryption password — so it
 * cannot perform a true restore (that runs on the agent). It can, however, prove
 * the stored container is well-formed and not truncated: a correct PROP header
 * followed by a sequence of length-prefixed encrypted frames that consume the
 * payload exactly to EOF. This catches truncated/garbled uploads, which the old
 * "size >= 60 bytes" check silently passed.
 *
 * Format (source of truth: properbackup-shared HeaderCodec / ProperCrypto):
 *   header  : "PROP"(4) + version(1) + salt(16) + nonce(12) + tag(16) = 49 bytes
 *   frame*  : int32 frameLen (big-endian) + nonce(12) + ciphertext+gcmTag(frameLen - 12)
 */
object ContainerInspector {

    private val MAGIC = byteArrayOf(0x50, 0x52, 0x4F, 0x50) // "PROP"
    private const val VERSION = 0x01
    private const val HEADER_SIZE = 49
    private const val NONCE_LEN = 12
    private const val GCM_TAG_LEN = 16
    private const val MIN_FRAME_LEN = NONCE_LEN + GCM_TAG_LEN // 28

    data class Result(val ok: Boolean, val frameCount: Int, val detail: String)

    fun inspect(data: ByteArray): Result {
        if (data.size < HEADER_SIZE) {
            return Result(false, 0, "Truncated header: ${data.size} bytes (need >= $HEADER_SIZE)")
        }
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) {
                return Result(false, 0, "Bad magic — not a ProperCrypto container")
            }
        }
        if ((data[4].toInt() and 0xFF) != VERSION) {
            return Result(false, 0, "Unsupported container version: ${data[4].toInt() and 0xFF}")
        }

        val dis = DataInputStream(ByteArrayInputStream(data, HEADER_SIZE, data.size - HEADER_SIZE))
        var frameCount = 0
        var remaining = data.size - HEADER_SIZE
        try {
            while (remaining > 0) {
                if (remaining < 4) {
                    return Result(false, frameCount, "Trailing bytes / truncated frame length after frame $frameCount")
                }
                val frameLen = dis.readInt()
                remaining -= 4
                if (frameLen < MIN_FRAME_LEN) {
                    return Result(false, frameCount, "Invalid frame length $frameLen at frame ${frameCount + 1} (min $MIN_FRAME_LEN)")
                }
                if (frameLen > remaining) {
                    return Result(false, frameCount, "Truncated frame ${frameCount + 1}: declared $frameLen but only $remaining byte(s) left")
                }
                if (dis.skipBytes(frameLen) != frameLen) {
                    return Result(false, frameCount, "Truncated frame ${frameCount + 1} while reading body")
                }
                remaining -= frameLen
                frameCount++
            }
        } catch (e: EOFException) {
            return Result(false, frameCount, "Unexpected EOF while parsing frames")
        }

        if (frameCount == 0) {
            return Result(false, 0, "No encrypted frames after header")
        }
        return Result(true, frameCount, "Container intact: $frameCount frame(s), ${data.size} bytes")
    }
}
