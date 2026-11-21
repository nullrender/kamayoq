package app.kamayoq.data

import java.io.File
import java.io.IOException

/**
 * The covers of works, one file per work in [folder], named by the work's id. The app's own files,
 * not its cache: Android may empty the cache, and the Library would lose every cover at once.
 */
class WorkCovers(private val folder: File) {

    // Made once per process: a draft here was left by one that died.
    init {
        folder.listFiles { file -> file.name.endsWith(".part") }.orEmpty().forEach(File::delete)
    }

    /** The cover kept for [workId], or null with none. */
    fun file(workId: String): File? = File(folder, workId).takeIf(File::isFile)

    /** Keeps [bytes] as [workId]'s cover. Written aside and renamed, so a cut never leaves half an image. */
    fun keep(workId: String, bytes: ByteArray) {
        folder.mkdirs()
        // A name of its own: two imports at once must not write into the same draft.
        val draft = File.createTempFile("$workId-", ".part", folder)
        try {
            draft.writeBytes(bytes)
            if (!draft.renameTo(File(folder, workId))) throw IOException("Could not keep the cover of $workId")
        } finally {
            draft.delete()
        }
    }

    fun drop(workId: String) {
        File(folder, workId).delete()
    }
}
