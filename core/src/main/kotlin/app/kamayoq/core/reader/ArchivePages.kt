package app.kamayoq.core.reader

import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.NaturalOrder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A file larger than [maxBytes] stopped being copied there, before filling the phone's storage. */
class FileTooLargeException(val maxBytes: Long) : IOException("File larger than $maxBytes bytes")

/** The pages of a chapter's archive, read straight from it while it stays open. */
class ArchiveChapter internal constructor(private val zip: ZipFile, private val entries: Map<String, ZipEntry>) :
    Closeable {
    /** Keys made here, in reading order (never the name inside the archive, which could point anywhere). */
    val pages: List<String> = entries.keys.toList()

    fun open(key: String): InputStream = zip.getInputStream(entries.getValue(key))

    override fun close() = zip.close()
}

/**
 * A CBZ or ZIP is copied once, hashed on the way, and the copy is opened by the index at its end.
 * Reading the index, not the entries one after another, is what opens Mihon's downloads: they store
 * images without compression and give each size only after its data (test of 25/10/2026).
 */
object ArchivePages {
    /**
     * Copies all of [input] to [output]; returns the same SHA-256 as `ContentCheck.hashOfFile` (rule
     * E1). Past [maxBytes] it stops with [FileTooLargeException].
     */
    fun copyHashing(input: InputStream, output: OutputStream, maxBytes: Long = Long.MAX_VALUE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, digest).copyTo(output, 64 * 1024)
        return ContentCheck.hex(digest.digest())
    }

    /**
     * Pages follow the natural order of their names in the archive. A file that is not a ZIP, or an
     * archive without images, is refused.
     */
    fun open(file: File): ArchiveChapter {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw IllegalArgumentException("Not a ZIP archive", e)
        }
        try {
            val pages = zip.entries().asSequence()
                .filter { !it.isDirectory && isPage(it.name) }
                .sortedWith(compareBy<ZipEntry, String>(NaturalOrder) { it.name })
                .toList()
            require(pages.isNotEmpty()) { "The archive has no images" }
            return ArchiveChapter(zip, pages.withIndex().associate { (index, entry) -> "page$index" to entry })
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    /** An image, outside hidden folders and the `__MACOSX` folder that macOS adds to archives. */
    private fun isPage(name: String): Boolean {
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it.startsWith('.') || it == "__MACOSX" }) return false
        return parts.last().substringAfterLast('.', "").lowercase() in FolderConvention.imageExtensions
    }
}
