package app.kamayoq.data.kavita

import app.kamayoq.data.StoredChapter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** [outdated] is true when a newer file was wanted and only this one could be had. */
class KavitaChapterFile(val file: File, val outdated: Boolean)

/**
 * The files of Kavita chapters, kept in [folder] to be read with no network. It sits in the app's
 * cache, so Android may free it, but apart from what the reader clears at start.
 */
class KavitaChapterFiles(private val client: KavitaClient, private val folder: File) {

    private val locks = ConcurrentHashMap<String, Any>()

    // Made once per process: a draft here was left by one that died, and no attempt reuses it.
    init {
        folder.listFiles { file -> file.name.endsWith(".part") }.orEmpty().forEach(File::delete)
    }

    fun file(address: String, key: String, chapter: StoredChapter): KavitaChapterFile =
        file(chapter, stillWanted = { true }) { address to key }

    /**
     * The chapter's file. Downloaded when there is none, or when the content must be checked and the
     * server can give a newer one; without the server, what is kept is still good to read. [server]
     * gives address and key, and may throw [IOException]: it is asked only for a download. Throws
     * [IOException] when there is neither. A download stops when [stillWanted] says no.
     */
    fun file(chapter: StoredChapter, stillWanted: () -> Boolean, server: () -> Pair<String, String>): KavitaChapterFile {
        val kept = File(folder, "${chapter.path}.cbz")
        if (kept.exists() && !chapter.needsContentCheck) return KavitaChapterFile(kept, outdated = false)
        // One at a time per chapter: leaving and opening again may start a second one, and the older
        // answer must not land on top of the newer.
        return synchronized(locks.computeIfAbsent(chapter.path) { Any() }) { download(chapter, kept, stillWanted, server) }
    }

    private fun download(
        chapter: StoredChapter,
        kept: File,
        stillWanted: () -> Boolean,
        server: () -> Pair<String, String>,
    ): KavitaChapterFile {
        folder.mkdirs()
        val draft = File.createTempFile("chapter-${chapter.path}-", ".part", folder)
        return try {
            val (address, key) = server()
            client.download(address, key, chapter.path, draft, stillWanted)
            if (!draft.renameTo(kept)) throw IOException("Could not keep the chapter ${chapter.path}")
            KavitaChapterFile(kept, outdated = false)
        } catch (e: IOException) {
            if (!kept.exists()) throw e
            KavitaChapterFile(kept, outdated = true)
        } finally {
            draft.delete()
        }
    }
}
