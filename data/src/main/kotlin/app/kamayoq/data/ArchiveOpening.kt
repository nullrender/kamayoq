package app.kamayoq.data

import app.kamayoq.core.reader.ArchiveChapter
import app.kamayoq.core.reader.ArchivePages
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.EpubLimits
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A chapter kept in an archive, ready to read. [chapterId] can differ from the one asked for (rule
 * E1). Its file stays open until the reader releases it and the last page being read is closed.
 */
class OpenedArchive internal constructor(
    val chapterId: String,
    private val archive: ArchiveChapter,
    internal val copy: File,
    private val onClosed: (OpenedArchive) -> Unit,
) {
    private var reading = 0
    private var released = false
    private var closed = false

    val pages: List<String> get() = archive.pages

    /** A page's data. An [IOException] once the chapter was released, which the reader shows as a broken page. */
    @Synchronized
    fun open(key: String): InputStream {
        if (released) throw IOException("The chapter was released")
        val page = archive.open(key)
        reading++
        return object : FilterInputStream(page) {
            private var done = false

            override fun close() {
                super.close()
                if (!done) {
                    done = true
                    finished()
                }
            }
        }
    }

    @Synchronized
    internal fun release() {
        released = true
        closeWhenIdle()
    }

    @Synchronized
    private fun finished() {
        reading--
        closeWhenIdle()
    }

    private fun closeWhenIdle() {
        if (!released || reading > 0 || closed) return
        closed = true
        archive.close()
        copy.delete()
        onClosed(this)
    }
}

/**
 * A book kept in a copy, ready for the text reader. [chapterId] can differ from the one asked for
 * (rule E1). The reader's page reads its entries until the reader releases it; a read after that
 * fails, and the page shows that section as missing.
 */
class OpenedBook internal constructor(
    val chapterId: String,
    val archive: EpubArchive,
    internal val copy: File,
    private val onClosed: (OpenedBook) -> Unit,
) {
    private var closed = false

    @Synchronized
    internal fun release() {
        if (closed) return
        closed = true
        archive.close()
        copy.delete()
        onClosed(this)
    }
}

/**
 * Opens chapters kept in a CBZ or ZIP: the file is copied once to [cache], hashed on the way, and
 * its pages are read from the copy (rule E1). Only the chapter being read is kept; nothing in
 * [cache] is ever the person's file. The app only hands over the file as the folder gives it.
 */
class ArchiveOpening(private val store: LibraryStore, private val cache: File) {

    // The chapter in the reader, an [OpenedArchive] or an [OpenedBook]. Only the release and open
    // functions change it, under this object's lock.
    private var current: Any? = null

    // How many openings started. The last one asked for is the one the person is on: an older one
    // that finishes after it closes itself.
    private val requests = AtomicLong()

    // Copies being written or still open. A concurrent set, changed without this object's lock: a
    // page closed on another thread frees its copy without waiting for an opening in progress.
    private val inUse: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /**
     * The reader is done with [opened]: its file closes once no page is being read, and its copy
     * leaves the cache.
     */
    @Synchronized
    fun release(opened: OpenedArchive) {
        if (current === opened) current = null
        opened.release()
    }

    /** The reader is done with [opened]: its file closes and its copy leaves the cache. */
    @Synchronized
    fun release(opened: OpenedBook) {
        if (current === opened) current = null
        opened.release()
    }

    private fun releaseCurrent() {
        when (val opened = current) {
            is OpenedArchive -> release(opened)
            is OpenedBook -> release(opened)
        }
    }

    /** Removes from the cache what no open chapter uses, such as copies left by a closed app. */
    fun clearLeftovers() {
        cache.listFiles()?.filter { it !in inUse }?.forEach { it.deleteRecursively() }
    }

    /**
     * Takes the number of a new opening, before anything slow: the caller asks the folder for the
     * file after this, and an older opening that was stuck there must not win.
     */
    fun begin(): Long = requests.incrementAndGet()

    /** Runs [write] unless another opening started after [request]: then throws [IOException]. */
    @Synchronized
    fun <T> whileLatest(request: Long, write: () -> T): T {
        if (requests.get() != request) throw IOException("A later chapter was opened")
        return write()
    }

    /**
     * The same as [open] for an EPUB: copied once, up to [EpubLimits.maxFileBytes], hashed on the
     * way, and its entries read from the copy.
     */
    fun openBook(request: Long, chapter: StoredChapter, input: InputStream, limits: EpubLimits = EpubLimits()): OpenedBook =
        throw UnsupportedOperationException("Not yet")

    /** Opens a chapter whose file is already at hand, with a number of its own. */
    fun open(chapter: StoredChapter, input: InputStream): OpenedArchive = open(begin(), chapter, input)

    /**
     * Reads [input] to the end and closes it. The chapter read before is released. The copy is made
     * outside the lock, so a reader leaving its chapter never waits for a long copy. Throws
     * [IOException] when another opening started after [request], the number from [begin]: the
     * person left this chapter.
     */
    fun open(request: Long, chapter: StoredChapter, input: InputStream): OpenedArchive {
        // A copy of its own: the one released before may still be read for a moment.
        val copy = File(cache.apply { mkdirs() }, "chapter-${UUID.randomUUID()}.cbz")
        inUse += copy
        var archive: ArchiveChapter? = null
        try {
            val hash = input.use { copy.outputStream().use { out -> ArchivePages.copyHashing(it, out) } }
            val pages = ArchivePages.open(copy)
            archive = pages
            // A superseded opening must not touch the store, or the old content would replace the
            // edition being read.
            val opened = whileLatest(request) {
                // Always checked, whatever the last scan said: the file may have changed since, and the list
                // can be opened before the scan ends. The hash is already made by the copy, so this
                // costs nothing more.
                val chapterId = store.confirmContent(chapter.id, hash)
                OpenedArchive(chapterId, pages, copy, onClosed = { inUse -= it.copy }).also {
                    releaseCurrent()
                    current = it
                }
            }
            return opened
        } catch (e: Exception) {
            archive?.close()
            copy.delete()
            inUse -= copy
            throw e
        }
    }
}
