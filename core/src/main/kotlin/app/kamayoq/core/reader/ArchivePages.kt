package app.kamayoq.core.reader

import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.NaturalOrder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A file larger than [maxBytes] stopped being copied there, before filling the phone's storage. */
class FileTooLargeException(val maxBytes: Long) : IOException("File larger than $maxBytes bytes")

/**
 * What a chapter's ComicInfo.xml says that the image reader's Automatic uses (the Anansi Project's
 * schema): the genre and tag items, and the `Manga` field.
 */
data class ComicInfo(val genre: List<String>, val tags: List<String>, val manga: MangaField?) {
    val words: List<String> get() = genre + tags

    companion object {
        val NONE = ComicInfo(emptyList(), emptyList(), null)

        /** Larger than any ComicInfo seen: past it the entry is left unread, as one that says nothing. */
        const val MAX_BYTES = 1024 * 1024

        /** The file as stored in the archive, in the encoding it declares. */
        fun parse(bytes: ByteArray): ComicInfo = parse(decode(bytes))

        // A byte order mark first, then the declaration's encoding, as an XML parser reads it; UTF-8
        // without either. Tools on Windows write UTF-16, and older ones ISO-8859-1.
        private fun decode(bytes: ByteArray): String {
            fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
            return when {
                at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                else -> {
                    // Without a byte order mark the declaration is in ASCII, whatever follows it.
                    val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
                    val declared = declaration.find(head)?.groupValues?.get(1)
                    val charset = declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() } ?: Charsets.UTF_8
                    String(bytes, charset)
                }
            }
        }

        private val declaration = Regex("""^\s*<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""")

        /** A document that is not ComicInfo, or not well formed, says nothing. */
        fun parse(text: String): ComicInfo {
            val root = XmlTree.parse(text)?.takeIf { it.localName == "ComicInfo" } ?: return NONE
            // Items separated by commas, as Kavita and the ComicInfo writers of Mihon and Komga write them.
            fun items(name: String) = root.child(name)?.text.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return ComicInfo(items("Genre"), items("Tags"), MangaField.of(root.child("Manga")?.text))
        }
    }
}

/** The pages of a chapter's archive, read straight from it while it stays open. */
class ArchiveChapter internal constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    /** What the archive's ComicInfo.xml says; nothing without one. */
    val comicInfo: ComicInfo = ComicInfo.NONE,
) : Closeable {
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
        val hashing = DigestInputStream(input, digest)
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = hashing.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > maxBytes) throw FileTooLargeException(maxBytes)
            output.write(buffer, 0, read)
        }
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
            return ArchiveChapter(zip, pages.withIndex().associate { (index, entry) -> "page$index" to entry }, comicInfoOf(zip))
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    /** The archive's ComicInfo.xml nearest its root; a missing, oversized or unreadable one says nothing. */
    private fun comicInfoOf(zip: ZipFile): ComicInfo {
        val entry = zip.entries().asSequence()
            .filter { !it.isDirectory && visible(it.name) && it.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
            .minByOrNull { it.name.count { char -> char == '/' } }
            ?: return ComicInfo.NONE
        if (entry.size > ComicInfo.MAX_BYTES) return ComicInfo.NONE
        return try {
            // Bounded by hand: an entry's size can be missing or wrong, and readNBytes needs Android 13.
            val bytes = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (bytes.size() <= ComicInfo.MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                }
            }
            if (bytes.size() > ComicInfo.MAX_BYTES) ComicInfo.NONE else ComicInfo.parse(bytes.toByteArray())
        } catch (e: IOException) {
            // The pages may still read: the metadata is a hint, never a reason to refuse the chapter.
            ComicInfo.NONE
        }
    }

    private fun visible(name: String): Boolean {
        val parts = name.split('/').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.none { it.startsWith('.') || it == "__MACOSX" }
    }

    /** An image, outside hidden folders and the `__MACOSX` folder that macOS adds to archives. */
    private fun isPage(name: String): Boolean {
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it.startsWith('.') || it == "__MACOSX" }) return false
        return parts.last().substringAfterLast('.', "").lowercase() in FolderConvention.imageExtensions
    }
}
