package pl.danielniemiec.properbackup.buffer.store

import java.io.IOException

interface ObjectStore {
    @Throws(IOException::class)
    fun put(objectName: String, data: ByteArray)

    @Throws(IOException::class)
    fun get(objectName: String): ByteArray

    @Throws(IOException::class)
    fun list(): List<String>

    @Throws(IOException::class)
    fun delete(objectName: String)
}
