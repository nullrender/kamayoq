package app.kamayoq.android

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.FolderEntry
import app.kamayoq.core.library.FolderWalk
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.data.ArchiveOpening
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.kavita.KavitaChapterFile
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One image of a chapter: its size, read from its header before the Strip lays it out. */
class PageImage(val width: Int?, val height: Int?, val open: () -> InputStream)

/**
 * A chapter ready for the Strip. [chapterId] can differ from the one asked for: see confirmContent.
 * The reader calls [release] when it leaves the chapter.
 */
class OpenedChapter(
    val chapterId: String,
    val pages: List<PageImage>,
    val progress: ChapterProgress,
    val release: () -> Unit,
)

/** A book ready for the text reader. The reader calls [release] when it leaves the book. */
class OpenedText(val chapterId: String, val archive: EpubArchive, val release: () -> Unit)

class ChapterMissingException : Exception("The chapter's file is no longer in the folder")

class ChapterDownloadException(cause: Throwable) : Exception("The chapter could not come from Kavita", cause)

/**
 * Opens a chapter read straight from the chosen folder, or the file of a Kavita chapter. A CBZ goes
 * to [ArchiveOpening], which copies it to the app's cache and checks its hash (rule E1). A folder of
 * images is read in place. Runs off the main thread.
 */
class ChapterOpening(
    context: Context,
    private val folder: ChosenFolder,
    private val store: LibraryStore,
    private val kavitaFile: (StoredChapter, () -> Boolean) -> KavitaChapterFile,
) {

    private val resolver = context.contentResolver

    private val archives = ArchiveOpening(store, File(context.cacheDir, "reader"))

    /**
     * One opening at a time in the whole app: a screen that left keeps its blocking work going, and
     * its file and reading turn must not come after a newer screen's.
     */
    val turns = OpeningTurns()

    /** At start: copies left in the cache by a closed app go; the chapter being read stays. */
    fun clearLeftovers() = archives.clearLeftovers()

    /** [stillWanted] says whether the screen that asked still waits: a download stops when it left. */
    fun open(tree: Uri?, chapter: StoredChapter, stillWanted: () -> Boolean): OpenedChapter {
        // First, before the folder is asked anything: an older opening stuck there loses to this one.
        val request = archives.begin()
        if (chapter.origin == LibraryStore.KAVITA) return openKavita(request, chapter, stillWanted)
        val library = tree?.let(folder::libraryId) ?: throw ChapterMissingException()
        val entry = FolderWalk.find(library, chapter.path) { folder.children(tree, it) }
            ?: throw ChapterMissingException()
        return when (chapter.format) {
            ChapterFormat.ARCHIVE -> openArchive(request, tree, chapter, entry)
            ChapterFormat.IMAGE_FOLDER -> openFolder(request, tree, chapter, entry)
            // An EPUB goes to openText; only Kavita brings the others, and its chapters never come here.
            ChapterFormat.EPUB, ChapterFormat.PDF, ChapterFormat.OTHER_ARCHIVE ->
                error("A local chapter of format ${chapter.format}")
        }
    }

    /** A local EPUB: copied to the app's cache, with a ceiling, and read from the copy (rule E1). */
    fun openText(tree: Uri?, chapter: StoredChapter): OpenedText {
        val request = archives.begin()
        val library = tree?.let(folder::libraryId) ?: throw ChapterMissingException()
        val entry = FolderWalk.find(library, chapter.path) { folder.children(tree, it) }
            ?: throw ChapterMissingException()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
        val input = checkNotNull(resolver.openInputStream(uri)) { "The folder provider gave no stream" }
        val opened = archives.openBook(request, chapter, input)
        return OpenedText(opened.chapterId, opened.archive) { archives.release(opened) }
    }

    private fun openArchive(request: Long, tree: Uri, chapter: StoredChapter, entry: FolderEntry): OpenedChapter {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
        val input = checkNotNull(resolver.openInputStream(uri)) { "The folder provider gave no stream" }
        return openStream(request, chapter, input)
    }

    private fun openKavita(request: Long, chapter: StoredChapter, stillWanted: () -> Boolean): OpenedChapter {
        val kept = try {
            kavitaFile(chapter, stillWanted)
        } catch (e: IOException) {
            throw ChapterDownloadException(e)
        }
        val opened = openStream(request, chapter, kept.file.inputStream())
        // Its hash may match what is stored: the newer file still has to come when there is a server.
        if (kept.outdated) store.doubtContent(opened.chapterId)
        return opened
    }

    private fun openStream(request: Long, chapter: StoredChapter, input: InputStream): OpenedChapter {
        val opened = archives.open(request, chapter, input)
        try {
            val pages = opened.pages.map { key -> page { opened.open(key) } }
            return OpenedChapter(opened.chapterId, pages, store.progress(opened.chapterId)) { archives.release(opened) }
        } catch (e: Exception) {
            // Reading a page header or the progress failed: the file must not stay open.
            archives.release(opened)
            throw e
        }
    }

    private fun openFolder(request: Long, tree: Uri, chapter: StoredChapter, entry: FolderEntry): OpenedChapter {
        val files = folder.children(tree, entry.id)
            .filter { !it.isFolder }
            .associateBy { "${chapter.path}/${it.name}" }
        // The same convention as the import decides which files are pages and in what order.
        val unit = FolderConvention.read(files.keys.toList())
            .works.flatMap { it.units }
            .firstOrNull { it.path == chapter.path }
        val paths = (unit?.content as? UnitContent.ImageFolder)?.pages.orEmpty()
        val open = { path: String ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, files.getValue(path).id)
            checkNotNull(resolver.openInputStream(uri)) { "The folder provider gave no stream for $path" }
        }
        val stampNow = unit?.takeIf { paths.isNotEmpty() }
            ?.let { ContentCheck.stampOf(it, files.mapValues { (_, file) -> FileStamp(file.size, file.modified) }) }
        val chapterId = store.confirmFolder(
            chapter.id,
            stampNow,
            hash = { ContentCheck.hashOfFolder(paths, open) },
            hashBeforeNames = { ContentCheck.hashOfFolderBeforeNames(paths, open) },
            whileLatest = { write -> archives.whileLatest(request, write) },
        )
        // Read in place from the folder: nothing to release.
        return OpenedChapter(chapterId, paths.map { path -> page { open(path) } }, store.progress(chapterId)) {}
    }

    /** Reads only the image header; a page whose size cannot be read shows as broken, not as a guess. */
    private fun page(open: () -> InputStream): PageImage {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, options) }
        val known = options.outWidth > 0 && options.outHeight > 0
        return PageImage(options.outWidth.takeIf { known }, options.outHeight.takeIf { known }, open)
    }
}
