package app.kamayoq.core.reader

/**
 * An element of a small XML document, with its namespace resolved. [children] keeps elements and
 * text in document order.
 */
internal class XmlElement(
    val namespace: String?,
    val localName: String,
    val attributes: List<XmlAttribute>,
) {
    val children = mutableListOf<Any>()

    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    fun child(name: String): XmlElement? = elements.firstOrNull { it.localName == name }

    fun children(name: String): List<XmlElement> = elements.filter { it.localName == name }

    /** An attribute with no namespace, as `getAttribute` finds for an unprefixed name. */
    fun attribute(name: String): String? = attributes.firstOrNull { it.namespace == null && it.localName == name }?.value

    fun attribute(namespace: String, name: String): String? =
        attributes.firstOrNull { it.namespace == namespace && it.localName == name }?.value

    /** Every descendant called [name], in document order. */
    fun descendants(name: String): List<XmlElement> {
        val found = mutableListOf<XmlElement>()
        val pending = ArrayDeque(elements.asReversed())
        while (pending.isNotEmpty()) {
            val element = pending.removeLast()
            if (element.localName == name) found += element
            for (child in element.elements.asReversed()) pending.addLast(child)
        }
        return found
    }

    /** As the DOM's `textContent`. */
    val text: String
        get() {
            val builder = StringBuilder()
            val pending = ArrayDeque<Any>(children.asReversed())
            while (pending.isNotEmpty()) {
                when (val node = pending.removeLast()) {
                    is String -> builder.append(node)
                    is XmlElement -> for (child in node.children.asReversed()) pending.addLast(child)
                }
            }
            return builder.toString()
        }
}

internal class XmlAttribute(val namespace: String?, val localName: String, val value: String)

/**
 * Reads the few XML documents of an EPUB the app itself needs (container, package, nav, NCX). Its
 * own reader, and not the platform's: the JVM of the tests and Android ship different XML parsers,
 * and this one never fetches a DTD or expands an entity a document declares. A document that is not
 * well formed gives null, as a browser gives the fork an error document with nothing in it.
 */
internal object XmlTree {
    const val MAX_CHARS = 16 * 1024 * 1024
    const val MAX_DEPTH = 256

    private class Open(val element: XmlElement, val rawName: String, val prefixes: Map<String, String>)

    fun parse(text: String): XmlElement? {
        if (text.length > MAX_CHARS) return null
        return try {
            Reader(text).document()
        } catch (_: Malformed) {
            null
        }
    }

    private class Malformed : Exception()

    private class Reader(private val text: String) {
        private var at = if (text.startsWith('﻿')) 1 else 0

        fun document(): XmlElement {
            var root: XmlElement? = null
            val open = ArrayDeque<Open>()
            while (at < text.length) {
                if (text[at] != '<') {
                    val end = text.indexOf('<', at).let { if (it < 0) text.length else it }
                    val raw = text.substring(at, end)
                    at = end
                    val parent = open.lastOrNull()
                    if (parent != null) parent.element.children += unescape(raw) else if (raw.isNotBlank()) throw Malformed()
                    continue
                }
                when {
                    text.startsWith("<!--", at) -> at = after("-->", at + 4)
                    text.startsWith("<?", at) -> at = after("?>", at + 2)
                    text.startsWith("<![CDATA[", at) -> {
                        val end = text.indexOf("]]>", at + 9).also { if (it < 0) throw Malformed() }
                        (open.lastOrNull() ?: throw Malformed()).element.children += text.substring(at + 9, end)
                        at = end + 3
                    }
                    text.startsWith("<!", at) -> skipDeclaration()
                    text.startsWith("</", at) -> {
                        val end = text.indexOf('>', at).also { if (it < 0) throw Malformed() }
                        val closed = open.removeLastOrNull() ?: throw Malformed()
                        if (text.substring(at + 2, end).trim() != closed.rawName) throw Malformed()
                        at = end + 1
                    }
                    else -> {
                        val (opened, selfClosing) = startTag(open.lastOrNull()?.prefixes ?: emptyMap())
                        val parent = open.lastOrNull()
                        if (parent != null) parent.element.children += opened.element
                        else if (root == null) root = opened.element
                        else throw Malformed()
                        if (!selfClosing) {
                            if (open.size >= MAX_DEPTH) throw Malformed()
                            open.addLast(opened)
                        }
                    }
                }
            }
            if (open.isNotEmpty()) throw Malformed()
            return root ?: throw Malformed()
        }

        private fun after(end: String, from: Int): Int {
            val found = text.indexOf(end, from)
            if (found < 0) throw Malformed()
            return found + end.length
        }

        /** A DOCTYPE, with its internal subset if it has one; nothing in it is read. */
        private fun skipDeclaration() {
            var depth = 0
            while (at < text.length) {
                when (text[at++]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) return
                }
            }
            throw Malformed()
        }

        private fun startTag(inherited: Map<String, String>): Pair<Open, Boolean> {
            at++
            val rawName = name()
            val raw = mutableListOf<Pair<String, String>>()
            var selfClosing = false
            while (true) {
                skipSpace()
                if (at >= text.length) throw Malformed()
                if (text[at] == '>') {
                    at++
                    break
                }
                if (text.startsWith("/>", at)) {
                    at += 2
                    selfClosing = true
                    break
                }
                val attribute = name()
                skipSpace()
                if (at >= text.length || text[at] != '=') throw Malformed()
                at++
                skipSpace()
                val quote = text.getOrNull(at)
                if (quote != '"' && quote != '\'') throw Malformed()
                val end = text.indexOf(quote, at + 1).also { if (it < 0) throw Malformed() }
                raw += attribute to attributeValue(text.substring(at + 1, end))
                at = end + 1
            }
            val prefixes = inherited.toMutableMap()
            for ((attribute, value) in raw) {
                if (attribute == "xmlns") prefixes[""] = value
                else if (attribute.startsWith("xmlns:")) prefixes[attribute.substring(6)] = value
            }
            val attributes = raw.filter { (attribute, _) -> attribute != "xmlns" && !attribute.startsWith("xmlns:") }
                .map { (attribute, value) ->
                    // An attribute without a prefix is in no namespace, whatever the default one is.
                    val prefix = attribute.substringBefore(':', "")
                    XmlAttribute(if (prefix.isEmpty()) null else prefixes[prefix], attribute.substringAfter(':'), value)
                }
            val namespace = prefixes[rawName.substringBefore(':', "")]?.ifEmpty { null }
            return Open(XmlElement(namespace, rawName.substringAfter(':'), attributes), rawName, prefixes) to selfClosing
        }

        private fun name(): String {
            val start = at
            while (at < text.length && !text[at].isWhitespace() && text[at] !in "=/><\"'") at++
            if (at == start) throw Malformed()
            return text.substring(start, at)
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }

    private val entity = Regex("""&(#[0-9]+|#x[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);""")

    private val xmlNames = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    // The HTML names the fork turns into characters before it parses, in any letter case
    // (`sanitizeXMLEntities` in epub.js). Any other name fails the document there, and here.
    private val htmlNames = mapOf(
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "hellip" to "…", "copy" to "©", "reg" to "®",
        "trade" to "™", "bull" to "•", "middot" to "·",
    )

    private val lineBreak = Regex("""\r\n|[\t\n\r]""")

    /** As an XML parser hands an attribute over: a tab or a line break written in it is a space. */
    private fun attributeValue(raw: String): String = unescape(raw.replace(lineBreak, " "))

    private fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        return entity.replace(raw) { match ->
            val body = match.groupValues[1]
            val code = when {
                body.startsWith("#x") -> body.substring(2).toIntOrNull(16)
                body.startsWith("#") -> body.substring(1).toIntOrNull()
                else -> return@replace htmlNames[body.lowercase()].takeIf { body.all(Char::isLetter) }
                    ?: xmlNames[body]
                    ?: throw Malformed()
            }
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
    }
}
