package app.kamayoq.data.kavita

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.data.StoredChapter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** [outdated] is true when a newer file was wanted and only this one could be had. */
class KavitaChapterFile(val file: File, val outdated: Boolean)

/**
 * The files of Kavita chapters, kept in [folder] to be read with no network. It sits in the app's
 * cache, so Android may free it, but apart from what the reader clears at start. The chapters the
 * person asks to download go to [downloads], in the app's own files, which Android never empties.
 */
class KavitaChapterFiles(
    private val client: KavitaClient,
    private val folder: File,
    private val downloads: File? = null,
) {

    private val locks = ConcurrentHashMap<String, Any>()

    // Made once per process: a draft here was left by one that died, and no attempt reuses it.
    init {
        listOfNotNull(folder, downloads).forEach { place ->
            place.listFiles { file -> file.name.endsWith(".part") }.orEmpty().forEach(File::delete)
        }
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
        // One at a time per chapter: leaving and opening again may start a second one, and the older
        // answer must not land on top of the newer. The copy is chosen inside the turn too: a
        // download asked from the Work screen may have just put it among the downloads.
        return synchronized(locks.computeIfAbsent(chapter.path) { Any() }) {
            // A downloaded copy comes first, and a newer content replaces it where it is.
            val kept = downloadedFile(chapter)?.takeIf(File::exists) ?: File(folder, nameOf(chapter))
            if (kept.exists() && !chapter.needsContentCheck) KavitaChapterFile(kept, outdated = false)
            else download(chapter, kept, stillWanted, server)
        }
    }

    /** True when the person downloaded [chapter] and its file is in [downloads]. */
    fun isDownloaded(chapter: StoredChapter): Boolean = downloadedFile(chapter)?.exists() == true

    /**
     * Downloads [chapter] into [downloads]; false when it was already there, so nothing came. A copy
     * the reader already keeps moves there with no network. Throws [IOException] as [file] does.
     */
    fun download(chapter: StoredChapter, stillWanted: () -> Boolean, server: () -> Pair<String, String>): Boolean {
        val target = checkNotNull(downloadedFile(chapter)) { "No folder for downloads" }
        return synchronized(locks.computeIfAbsent(chapter.path) { Any() }) {
            if (target.exists()) return@synchronized false
            target.parentFile.mkdirs()
            val cached = File(folder, nameOf(chapter))
            if (cached.exists() && !chapter.needsContentCheck && cached.renameTo(target)) return@synchronized true
            val draft = File.createTempFile("chapter-${chapter.path}-", ".part", target.parentFile)
            try {
                val (address, key) = server()
                client.download(address, key, chapter.path, draft, stillWanted)
                if (!draft.renameTo(target)) throw IOException("Could not keep the chapter ${chapter.path}")
                true
            } finally {
                draft.delete()
            }
        }
    }

    /** Deletes every copy of [chapter] on this device and returns the bytes freed. */
    fun delete(chapter: StoredChapter): Long = synchronized(locks.computeIfAbsent(chapter.path) { Any() }) {
        listOfNotNull(downloadedFile(chapter), File(folder, nameOf(chapter))).filter(File::exists).sumOf { copy ->
            val bytes = copy.length()
            if (copy.delete()) bytes else 0L
        }
    }

    /** The bytes the downloaded copies of [chapters] take. */
    fun downloadedBytes(chapters: List<StoredChapter>): Long =
        chapters.mapNotNull(::downloadedFile).filter(File::exists).sumOf(File::length)

    private fun downloadedFile(chapter: StoredChapter): File? = downloads?.let { File(it, nameOf(chapter)) }

    private fun nameOf(chapter: StoredChapter) = "${chapter.path}.${extensionOf(chapter.format)}"

    // Only Kavita's CBZ chapters and books reach here: the reader stops the other formats first.
    private fun extensionOf(format: ChapterFormat) = if (format == ChapterFormat.EPUB) "epub" else "cbz"

    private fun download(
        chapter: StoredChapter,
        kept: File,
        stillWanted: () -> Boolean,
        server: () -> Pair<String, String>,
    ): KavitaChapterFile {
        kept.parentFile.mkdirs()
        val draft = File.createTempFile("chapter-${chapter.path}-", ".part", kept.parentFile)
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
