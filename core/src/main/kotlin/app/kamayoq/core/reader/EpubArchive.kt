package app.kamayoq.core.reader

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Provisional until measured with real books on a real phone (step 4, piece 3). */
class EpubLimits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
)

/**
 * A book's files, found only by their exact names and never extracted under a name from the ZIP.
 * Each entry stops at the size the index declared, so a false size cannot pass the limits below.
 */
class EpubArchive private constructor(private val zip: ZipFile, private val entries: Map<String, ZipEntry>) : Closeable {
    val names: Set<String> = entries.keys

    fun sizeOf(name: String): Long = entries.getValue(name).size

    fun open(name: String): InputStream {
        val entry = entries[name] ?: throw NoSuchElementException("No entry with that name")
        return StopsAtSize(zip.getInputStream(entry), entry.size)
    }

    override fun close() = zip.close()

    companion object {
        private const val CONTAINER = "META-INF/container.xml"

        fun open(file: File, limits: EpubLimits = EpubLimits()): EpubArchive {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw IllegalArgumentException("Not a ZIP archive", e)
            }
            try {
                return EpubArchive(zip, checkedEntries(zip, limits))
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private fun checkedEntries(zip: ZipFile, limits: EpubLimits): Map<String, ZipEntry> {
            require(zip.size() <= limits.maxEntries) { "Too many entries" }
            val all = zip.entries().asSequence().toList()
            require(all.map { it.name }.toSet().size == all.size) { "Repeated entry name" }
            val usable = all.filter { !it.isDirectory && isSafeName(it.name) }
            // Any other method throws later, inside the WebView's request thread, where it ends the app.
            require(usable.all { it.method == ZipEntry.STORED || it.method == ZipEntry.DEFLATED }) { "Unsupported compression" }
            require(usable.all { it.size in 0..limits.maxEntryBytes }) { "Entry too large" }
            require(usable.sumOf { it.size } <= limits.maxTotalBytes) { "Book too large" }
            require(usable.any { it.name == CONTAINER }) { "Not an EPUB: no $CONTAINER" }
            return usable.associateBy { it.name }
        }

        private fun isSafeName(name: String): Boolean =
            !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." || it.isEmpty() }
    }
}

private class StopsAtSize(input: InputStream, private var left: Long) : FilterInputStream(input) {
    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = `in`.read(target, offset, minOf(length.toLong(), left + 1).toInt())
        if (read < 0) return read
        if (read > left) throw IOException("Entry larger than the index says")
        left -= read
        return read
    }
}
