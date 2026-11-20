package app.kamayoq.data

import java.io.File
import java.io.IOException

/**
 * The covers of works, one file per work in [folder], named by the work's id. The app's own files,
 * not its cache: Android may empty the cache, and the Library would lose every cover at once.
 */
class WorkCovers(private val folder: File) {

    /** The cover kept for [workId], or null with none. */
    fun file(workId: String): File? = File(folder, workId).takeIf(File::isFile)

    /** Keeps [bytes] as [workId]'s cover. Written aside and renamed, so a cut never leaves half an image. */
    fun keep(workId: String, bytes: ByteArray) {
        folder.mkdirs()
        val draft = File(folder, "$workId.part")
        draft.writeBytes(bytes)
        if (!draft.renameTo(File(folder, workId))) {
            draft.delete()
            throw IOException("Could not keep the cover of $workId")
        }
    }

    fun drop(workId: String) {
        File(folder, workId).delete()
    }
}
