package app.kamayoq.core.reader

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One item of a book's table of contents, as the fork lists it; [subitems] is empty for a leaf. */
data class ContentsEntry(val label: String, val href: String?, val subitems: List<ContentsEntry>)

/**
 * A chapter of a file split by its table of contents (`docs/decisions/novel-chapters-2026-11-18.md`).
 * [key] is the item's destination, a path inside the EPUB with its fragment; [volume] the title of
 * the level above, when there is one.
 */
data class ContentsChapter(val title: String, val key: String, val volume: String?)

/**
 * A book's table of contents and the chapters it gives. No [chapters] means the file does not carry
 * the division: it is one chapter, the whole file. [chapterOfItem] says, for the number the fork
 * gives each item (counted from 0, parents before their subitems), which chapter a page under that
 * item is in.
 */
data class BookContents(
    val entries: List<ContentsEntry>,
    val chapters: List<ContentsChapter>,
    val chapterOfItem: List<Int>,
) {
    companion object {
        val UNDIVIDED = BookContents(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Reads a book's table of contents the way the fork does (`third_party/foliate-js/epub.js`, `init`):
 * the package named by the container, the `nav` of type `toc`, and the NCX when that `nav` has no
 * item with a destination. Works on text handed in by name, so it runs the same on the JVM and on
 * the phone, and nothing outside the book is ever read.
 */
object EpubContents {
    const val MAX_ITEMS = 20_000

    private const val CONTAINER = "META-INF/container.xml"
    private const val PACKAGE_TYPE = "application/oebps-package+xml"
    private const val NCX_TYPE = "application/x-dtbncx+xml"
    private const val EPUB_NAMESPACE = "http://www.idpf.org/2007/ops"

    private class Item(val id: String?, val href: String, val mediaType: String?, val properties: List<String>)

    /** [load] gives the text of the entry with that exact name, or null when there is none. */
    fun read(load: (String) -> String?): BookContents {
        val container = document(load, CONTAINER) ?: return BookContents.UNDIVIDED
        val packagePath = container.descendants("rootfile")
            .firstOrNull { it.attribute("media-type") == PACKAGE_TYPE }
            ?.attribute("full-path") ?: return BookContents.UNDIVIDED
        val opf = document(load, packagePath) ?: return BookContents.UNDIVIDED
        val manifest = opf.child("manifest")?.children("item").orEmpty().mapNotNull { item ->
            val href = item.attribute("href")?.let { resolve(it, packagePath) } ?: return@mapNotNull null
            Item(
                id = item.attribute("id"),
                href = href,
                mediaType = item.attribute("media-type")?.substringBefore(';')?.trim()?.lowercase(),
                properties = item.attribute("properties")?.split(whitespace).orEmpty(),
            )
        }
        val spine = opf.child("spine") ?: return BookContents.UNDIVIDED
        val byId = manifest.filter { it.id != null }.associateBy { it.id }
        val inSpine = spine.children("itemref").mapNotNull { byId[it.attribute("idref")]?.href }.toSet()

        val navPath = manifest.firstOrNull { "nav" in it.properties }?.href
        val ncxPath = (spine.attribute("toc")?.let(byId::get) ?: manifest.firstOrNull { it.mediaType == NCX_TYPE })?.href
        var entries = navPath?.let { path -> attempt { document(load, path)?.let { nav(it, path) } } }
        if (!hasDestination(entries) && ncxPath != null) {
            attempt { document(load, ncxPath)?.let { ncx(it, ncxPath) } }?.let { entries = it }
        }
        return contents(entries.orEmpty(), inSpine)
    }

    private fun contents(entries: List<ContentsEntry>, inSpine: Set<String>): BookContents {
        val chapters = mutableListOf<ContentsChapter>()
        val byKey = HashMap<String, Int>()
        // For each item, in the fork's numbering: its chapter, or null for one that is none.
        val own = mutableListOf<Int?>()
        fun visit(items: List<ContentsEntry>, volume: String?, depth: Int) {
            for (item in items) {
                if (own.size >= MAX_ITEMS || depth > XmlTree.MAX_DEPTH) throw TooLarge()
                val key = item.href?.takeIf { item.subitems.isEmpty() && it.substringBefore('#') in inSpine }
                if (key == null) {
                    own += null
                } else {
                    // Two items with the same destination are one chapter, under the first title.
                    own += byKey.getOrPut(key) {
                        chapters += ContentsChapter(item.label, key, volume)
                        chapters.lastIndex
                    }
                }
                visit(item.subitems, item.label.ifEmpty { volume }, depth + 1)
            }
        }
        try {
            visit(entries, volume = null, depth = 0)
        } catch (_: TooLarge) {
            return BookContents.UNDIVIDED
        }
        if (chapters.isEmpty()) return BookContents(entries, emptyList(), emptyList())
        // A heading leads into the chapter after it; past the last chapter, a page is still in it.
        val chapterOfItem = MutableList(own.size) { chapters.lastIndex }
        var next = chapters.lastIndex
        for (index in own.indices.reversed()) {
            own[index]?.let { next = it }
            chapterOfItem[index] = next
        }
        return BookContents(entries, chapters, chapterOfItem)
    }

    private class TooLarge : Exception()

    private class Refused : Exception()

    /** The fork drops a table of contents it fails to read, and goes on with what it had. */
    private fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (_: Refused) {
        null
    }

    private fun document(load: (String) -> String?, name: String): XmlElement? = load(name)?.let(XmlTree::parse)

    private fun hasDestination(entries: List<ContentsEntry>?): Boolean =
        entries.orEmpty().any { !it.href.isNullOrEmpty() || hasDestination(it.subitems) }

    private fun nav(document: XmlElement, path: String): List<ContentsEntry>? {
        fun list(ol: XmlElement?, depth: Int): List<ContentsEntry> {
            if (ol == null) return emptyList()
            if (depth > XmlTree.MAX_DEPTH) throw Refused()
            return ol.children("li").map { li ->
                val anchor = li.child("a") ?: li.child("span")
                val label = anchor?.let { normalize(it.text).ifEmpty { it.attribute("title").orEmpty() } }.orEmpty()
                ContentsEntry(label, destination(anchor?.attribute("href"), path), list(li.child("ol"), depth + 1))
            }
        }
        // The first `nav` of type `toc` that has a list.
        return document.descendants("nav")
            .filter { "toc" in it.attribute(EPUB_NAMESPACE, "type")?.split(whitespace).orEmpty() }
            .firstNotNullOfOrNull { nav -> nav.child("ol")?.let { list(it, 0) } }
    }

    private fun ncx(document: XmlElement, path: String): List<ContentsEntry>? {
        fun point(element: XmlElement, depth: Int): ContentsEntry {
            if (depth > XmlTree.MAX_DEPTH) throw Refused()
            // The fork reads the source of a point without checking that it has one, and fails there.
            val content = element.child("content") ?: throw Refused()
            return ContentsEntry(
                label = normalize(element.child("navLabel")?.text.orEmpty()),
                href = destination(content.attribute("src"), path),
                subitems = element.children("navPoint").map { point(it, depth + 1) },
            )
        }
        return document.child("navMap")?.children("navPoint")?.map { point(it, 0) }
    }

    private fun destination(href: String?, base: String): String? {
        if (href.isNullOrEmpty()) return null
        return decode(resolve(href, base), keep = ";/?:@&=+$,#") ?: throw Refused()
    }

    private val whitespace = Regex("""\s""")
    private val external = Regex("""^(?!blob)\w+:""", RegexOption.IGNORE_CASE)
    private val asciiSpace = Regex("""[\t\n\u000C\r ]+""")

    private fun normalize(text: String) = text.replace(asciiSpace, " ").trim(' ')

    /**
     * The fork's `resolveURL`: a link out of the book stays as written; any other is taken from the
     * folder of [base], loses its query and has its escapes decoded, but for `/` and `#`.
     */
    internal fun resolve(url: String, base: String): String {
        if (external.containsMatchIn(url)) return url
        // What a browser's URL parser does before it resolves: no tab or line break, `\` as `/`.
        val cleaned = url.trim { it <= ' ' }.filter { it != '\t' && it != '\n' && it != '\r' }
        val fragment = cleaned.substringAfter('#', "").takeIf { '#' in cleaned }
        val path = cleaned.substringBefore('#').substringBefore('?').replace('\\', '/')
        val walked = when {
            // Only a fragment, or nothing: the document itself.
            path.isEmpty() -> base.split('/')
            path.startsWith('/') -> path.split('/')
            else -> base.split('/').dropLast(1) + path.split('/')
        }
        val segments = ArrayDeque<String>()
        for (segment in walked) {
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        val resolved = segments.joinToString("/") + if (fragment != null) "#$fragment" else ""
        return decode(resolved.replace(keptEscape, "%25$1"), keep = "") ?: resolved
    }

    private val keptEscape = Regex("""%(2[fF]|23)""")
    private const val hexDigits = "0123456789abcdefABCDEF"

    /** As `decodeURI`, which leaves the escapes of [keep] alone; null for a malformed escape. */
    private fun decode(text: String, keep: String): String? {
        if ('%' !in text) return text
        val result = StringBuilder()
        var at = 0
        while (at < text.length) {
            if (text[at] != '%') {
                result.append(text[at++])
                continue
            }
            val bytes = ArrayList<Byte>()
            val start = at
            while (at < text.length && text[at] == '%') {
                val digits = text.substring(minOf(at + 1, text.length), minOf(at + 3, text.length))
                if (digits.length != 2 || digits.any { it !in hexDigits }) return null
                val byte = digits.toInt(16)
                bytes += byte.toByte()
                at += 3
                if (bytes.size == sequenceLength(bytes[0])) break
            }
            if (bytes.size != sequenceLength(bytes[0])) return null
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            result.append(if (decoded.length == 1 && decoded[0] in keep) text.substring(start, at) else decoded)
        }
        return result.toString()
    }

    private fun sequenceLength(first: Byte): Int {
        val value = first.toInt() and 0xff
        return when {
            value < 0x80 -> 1
            value in 0xc0..0xdf -> 2
            value in 0xe0..0xef -> 3
            value in 0xf0..0xf7 -> 4
            else -> 0
        }
    }
}
