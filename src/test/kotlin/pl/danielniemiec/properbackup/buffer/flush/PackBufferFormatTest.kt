package pl.danielniemiec.properbackup.buffer.flush

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PackBufferFormatTest {

    @Test
    fun parsePackDataValidSingleEntry() {
        val raw = buildPack(listOf("obj-1.enc" to "hello world".toByteArray()))
        val entries = PackBuffer.parsePackData(raw)
        assertEquals(1, entries.size)
        assertEquals("obj-1.enc", entries[0].objectName)
        assertArrayEquals("hello world".toByteArray(), entries[0].data)
    }

    @Test
    fun parsePackDataMultipleEntries() {
        val raw = buildPack(
            listOf(
                "a.enc" to byteArrayOf(1, 2, 3),
                "b.enc" to byteArrayOf(4, 5),
                "c.enc" to ByteArray(0),
            ),
        )
        val entries = PackBuffer.parsePackData(raw)
        assertEquals(3, entries.size)
        assertEquals("a.enc", entries[0].objectName)
        assertArrayEquals(byteArrayOf(1, 2, 3), entries[0].data)
        assertEquals("b.enc", entries[1].objectName)
        assertArrayEquals(byteArrayOf(4, 5), entries[1].data)
        assertEquals("c.enc", entries[2].objectName)
        assertEquals(0, entries[2].data.size)
    }

    @Test
    fun parsePackDataInvalidMagicThrows() {
        val bad = ByteArray(10)
        assertThrows(IllegalArgumentException::class.java) {
            PackBuffer.parsePackData(bad)
        }
    }

    @Test
    fun parsePackDataUnsupportedVersionThrows() {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeInt(PackBuffer.MAGIC)
        dos.writeShort(99)
        dos.flush()
        assertThrows(IllegalArgumentException::class.java) {
            PackBuffer.parsePackData(buf.toByteArray())
        }
    }

    @Test
    fun parsePackDataEmptyPackNoEntries() {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeInt(PackBuffer.MAGIC)
        dos.writeShort(PackBuffer.VERSION)
        dos.flush()
        val entries = PackBuffer.parsePackData(buf.toByteArray())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun parsePackDataWithEndMarker() {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeInt(PackBuffer.MAGIC)
        dos.writeShort(PackBuffer.VERSION)
        val name = "test.enc".toByteArray()
        val data = "payload".toByteArray()
        dos.writeInt(name.size)
        dos.write(name)
        dos.writeInt(data.size)
        dos.write(data)
        dos.writeInt(0) // end marker
        dos.flush()
        val entries = PackBuffer.parsePackData(buf.toByteArray())
        assertEquals(1, entries.size)
        assertEquals("test.enc", entries[0].objectName)
    }

    @Test
    fun packEntryEqualityAndHashCode() {
        val a = PackBuffer.PackEntry("x", byteArrayOf(1, 2))
        val b = PackBuffer.PackEntry("x", byteArrayOf(1, 2))
        val c = PackBuffer.PackEntry("x", byteArrayOf(3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun parsePackDataLargePayload() {
        val bigData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val raw = buildPack(listOf("large.enc" to bigData))
        val entries = PackBuffer.parsePackData(raw)
        assertEquals(1, entries.size)
        assertArrayEquals(bigData, entries[0].data)
    }

    private fun buildPack(entries: List<Pair<String, ByteArray>>): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeInt(PackBuffer.MAGIC)
        dos.writeShort(PackBuffer.VERSION)
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            dos.writeInt(nameBytes.size)
            dos.write(nameBytes)
            dos.writeInt(data.size)
            dos.write(data)
        }
        dos.flush()
        return buf.toByteArray()
    }
}
