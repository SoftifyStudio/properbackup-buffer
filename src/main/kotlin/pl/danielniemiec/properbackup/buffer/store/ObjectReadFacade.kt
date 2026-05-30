package pl.danielniemiec.properbackup.buffer.store

import pl.danielniemiec.properbackup.buffer.flush.PackBuffer
import pl.danielniemiec.properbackup.buffer.ovh.SwiftClient
import java.io.IOException
import java.util.ArrayList
import java.util.TreeSet

class ObjectReadFacade(
    private val local: ObjectStore,
    private val archive: SwiftClient?,
    private val packBuffer: PackBuffer? = null,
) {

    @Throws(IOException::class)
    fun listMerged(): List<String> {
        val names = TreeSet(local.list())
        archive?.list()?.let { names.addAll(it) }
        return ArrayList(names)
    }

    @Throws(IOException::class)
    fun getMerged(objectName: String): ByteArray {
        // 1. Try local object store (individual files on disk)
        try {
            return local.get(objectName)
        } catch (localEx: IOException) {
            // 2. Try pack buffer (object may be inside a local pack)
            val pb = packBuffer
            if (pb != null) {
                val fromPack = pb.readObjectFromAnyServer(objectName)
                if (fromPack != null) return fromPack
            }

            // 3. Try remote archive (OVH Swift)
            if (archive != null) {
                try {
                    return archive.get(objectName)
                } catch (archiveEx: IOException) {
                    localEx.addSuppressed(archiveEx)
                }
            }

            throw IOException("Object not found in local store, pack buffer, or archive: $objectName", localEx)
        }
    }
}
