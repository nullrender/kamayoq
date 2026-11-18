package app.kamayoq.data

import app.kamayoq.core.reader.ArchivePages
import app.kamayoq.core.reader.BookContents
import app.kamayoq.core.reader.ContentsChapter
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.EpubContents
import app.kamayoq.core.reader.EpubLimits
import app.kamayoq.core.reader.FileTooLargeException
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Reads the table of contents of an EPUB file, for the reading of the folder and for the reader. */
object SplitContents {
    /** The documents that hold a table of contents are small: a larger entry is not read as one. */
    const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024

    /**
     * The chapters of the file in [input], which is read to the end and closed. The file is copied
     * to [cache] to be read as an archive, and the copy leaves when this returns. A file that is no
     * EPUB, or is above the [limits], has no chapters: it stays whole, and opening it says why.
     */
    fun read(input: InputStream, cache: File, limits: EpubLimits = EpubLimits()): List<ContentsChapter> {
        val copy = File(cache.apply { mkdirs() }, "contents-${UUID.randomUUID()}.epub")
        try {
            input.use { copy.outputStream().use { out -> ArchivePages.copyHashing(it, out, limits.maxFileBytes) } }
            return EpubArchive.open(copy, limits).use { of(it).chapters }
        } catch (_: IllegalArgumentException) {
            // Not a ZIP, not an EPUB, or past a ceiling of the archive.
            return emptyList()
        } catch (_: FileTooLargeException) {
            return emptyList()
        } finally {
            copy.delete()
        }
    }

    /** The table of contents of an open book. */
    fun of(archive: EpubArchive): BookContents = EpubContents.read { name ->
        if (name in archive.names && archive.sizeOf(name) <= MAX_DOCUMENT_BYTES) {
            archive.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            null
        }
    }
}
