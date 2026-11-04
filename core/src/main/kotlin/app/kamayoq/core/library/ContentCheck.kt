package app.kamayoq.core.library

import java.io.InputStream
import java.security.MessageDigest

/** Size and last change of a file, as the folder listing gives them at no cost. */
data class FileStamp(val size: Long, val modified: Long?)

/**
 * Whether a reading unit at the same path still has the same content (rule E1, decision of
 * 25/10/2026). The stamp is cheap and only a hint; the hash reads the whole content and decides.
 */
object ContentCheck {
    /**
     * One text per unit from the stamps of its files; a different text means the unit may have
     * changed. Every file of the unit must have a stamp.
     */
    fun stampOf(unit: ReadingUnit, files: Map<String, FileStamp>): String {
        val content = unit.content
        val paths = when (content) {
            UnitContent.Archive -> listOf(unit.path)
            is UnitContent.ImageFolder -> content.pages
        }
        val stamps = paths.map { requireNotNull(files[it]) { "No stamp for $it" } }
        return when (content) {
            UnitContent.Archive -> "file:${stamps[0].size}:${stamps[0].modified ?: UNKNOWN}"
            is UnitContent.ImageFolder -> {
                val newest = stamps.mapNotNull { it.modified }.maxOrNull() ?: UNKNOWN
                // The names too: renaming pages keeps size and date but changes their order, and a
                // saved position would fall on another page.
                "folder:${stamps.size}:${stamps.sumOf { it.size }}:$newest:${paths.hashCode()}"
            }
        }
    }

    /** SHA-256 of a file's whole content, in lowercase hexadecimal. */
    fun hashOfFile(input: InputStream): String = hex(digestOf(input))

    /**
     * SHA-256 of a folder of images: each page's name and content, in reading order. Only the file's
     * name counts, not its folders: renaming a work's folder does not change its content (decision
     * of 30/10/2026).
     */
    fun hashOfFolder(pages: List<String>, open: (String) -> InputStream): String =
        hashOfFolder(pages, open) { it.substringAfterLast('/') }

    /** The summary kept before 30/10/2026, with the whole path, to recognize a chapter read since then. */
    fun hashOfFolderBeforeNames(pages: List<String>, open: (String) -> InputStream): String =
        hashOfFolder(pages, open) { it }

    private fun hashOfFolder(pages: List<String>, open: (String) -> InputStream, nameOf: (String) -> String): String {
        val digest = MessageDigest.getInstance(SHA_256)
        for (page in pages) {
            // A name never holds a NUL, and each page's own hash has a fixed length, so no name and
            // content can run into the next ones.
            digest.update(nameOf(page).toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(open(page).use(::digestOf))
        }
        return hex(digest.digest())
    }

    private const val SHA_256 = "SHA-256"
    private const val UNKNOWN = "-"

    private fun digestOf(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
