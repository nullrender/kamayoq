package app.kamayoq.core.reader

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The book open right now; [token] is new at each opening, so an old address stops answering. */
class OpenBook(val token: String, val entries: Set<String>)

/**
 * What the interceptor answers. There is no null and no redirect on purpose: a null would send the
 * request to the internet, and after a redirect the CSP stops checking paths (CSP3, section 7.6).
 */
sealed interface ReaderRoute {
    data class ReaderFile(val path: String) : ReaderRoute
    data object BookIndex : ReaderRoute
    data class BookEntry(val name: String) : ReaderRoute
    data object NotFound : ReaderRoute
}

/** Denies by default: only exact names of the reader's files and of the open book get an answer. */
class ReaderRoutes(private val readerFiles: Set<String>, private val book: OpenBook?) {
    fun decide(url: String): ReaderRoute {
        val path = url.removePrefixOrNull("${ReaderPolicy.ORIGIN}/") ?: return ReaderRoute.NotFound
        if ('?' in path || '#' in path) return ReaderRoute.NotFound

        path.removePrefixOrNull("reader/")?.let { file ->
            return if (file in readerFiles) ReaderRoute.ReaderFile(file) else ReaderRoute.NotFound
        }

        val openBook = book ?: return ReaderRoute.NotFound
        val inBook = path.removePrefixOrNull("book/${openBook.token}/") ?: return ReaderRoute.NotFound
        if (inBook == "index.json") return ReaderRoute.BookIndex
        val encoded = inBook.removePrefixOrNull("e/") ?: return ReaderRoute.NotFound
        val name = entryName(encoded) ?: return ReaderRoute.NotFound
        return if (name in openBook.entries) ReaderRoute.BookEntry(name) else ReaderRoute.NotFound
    }

    /** Each segment decoded once, on its own, so an encoded slash or dot never becomes a separator. */
    private fun entryName(encoded: String): String? {
        val segments = encoded.split('/').map { percentDecoded(it) ?: return null }
        val unsafe = segments.any { segment ->
            segment.isEmpty() || segment == "." || segment == ".." || segment.any { it == '/' || it == '\\' || it == '\u0000' }
        }
        return if (unsafe) null else segments.joinToString("/")
    }

    /** Not `URLDecoder`: it turns `+` into a space, which is form encoding and not a path's. */
    private fun percentDecoded(segment: String): String? {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            if (char == '%') {
                val high = segment.getOrNull(index + 1)?.digitToIntOrNull(16) ?: return null
                val low = segment.getOrNull(index + 2)?.digitToIntOrNull(16) ?: return null
                bytes.write(high * 16 + low)
                index += 3
            } else {
                // The WebView percent-encodes everything else, so anything above ASCII is not its URL.
                if (char.code > 127) return null
                bytes.write(char.code)
                index += 1
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null
}
