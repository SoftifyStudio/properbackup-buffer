package pl.danielniemiec.properbackup.buffer.store

import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import java.io.IOException
import java.util.ArrayList
import java.util.TreeSet

class ObjectReadFacade(private val local: ObjectStore, private val archive: SwiftClient?) {

    @Throws(IOException::class)
    fun listMerged(): List<String> {
        val names = TreeSet(local.list())
        archive?.list()?.let { names.addAll(it) }
        return ArrayList(names)
    }

    @Throws(IOException::class)
    fun getMerged(objectName: String): ByteArray {
        return try {
            local.get(objectName)
        } catch (primary: IOException) {
            if (archive == null) throw primary
            try {
                archive.get(objectName)
            } catch (secondary: IOException) {
                primary.addSuppressed(secondary)
                throw primary
            }
        }
    }
}
