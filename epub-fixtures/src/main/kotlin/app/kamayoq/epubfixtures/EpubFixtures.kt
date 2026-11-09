package app.kamayoq.epubfixtures

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small books with invented text. A generated file only proves what the generator writes: obfuscated
 * fonts, DOCTYPEs and malformed XHTML appear only with real books, on a real phone.
 */
object EpubFixtures {
    private const val PACKAGE = "OEBPS/content.opf"
    private const val MINIMUM_COMPILED_BYTES = 512 * 1024

    // A fixed date so the same book always gives the same bytes.
    private const val ENTRY_TIME = 1_767_225_600_000L

    /** EPUB 3 with three sections, each one in the spine and in the table of contents. */
    fun short(): ByteArray {
        val sections = (1..3).map { number ->
            Section("s$number.xhtml", "Seção $number", paragraphs(number, 12) +
                """<p id="book-color" style="color: rgb(23, 67, 89)">Cor do livro.</p>""")
        }
        return epub3("Livro curto", sections, sections.map { TocItem(it.title, it.file) })
    }

    /** A novel compiled into one spine item above 512 KiB, with its chapters only as anchors. */
    fun compiled(): ByteArray {
        val chapters = 40
        val body = (1..chapters).joinToString("\n") { number ->
            """<h2 id="cap-$number">Capítulo $number</h2>""" + "\n" + paragraphs(number, MINIMUM_COMPILED_BYTES / chapters / PARAGRAPH_BYTES + 1)
        }
        val section = Section("s1.xhtml", "Novela compilada", body)
        return epub3("Novela compilada", listOf(section), (1..chapters).map { TocItem("Capítulo $it", "s1.xhtml#cap-$it") })
    }

    /** EPUB 2: the table of contents comes from an NCX, and there is no nav document. */
    fun epub2WithNcx(): ByteArray {
        val sections = (1..2).map { number -> Section("s$number.xhtml", "Parte $number", paragraphs(number, 6)) }
        val manifest = """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" + "\n" + sectionItems(sections)
        return zipped(
            sections,
            PACKAGE to packageDocument("2.0", "Livro antigo", metadata = "", manifest, """<spine toc="ncx">""", sections),
            "OEBPS/toc.ncx" to ncx(sections),
        )
    }

    /** A fixed-layout book, which the reader does not open in step 4 and must say so. */
    fun fixedLayout(): ByteArray {
        val section = Section("s1.xhtml", "Página fixa", paragraphs(1, 1))
        return epub3(
            "Livro de página fixa",
            listOf(section),
            listOf(TocItem(section.title, section.file)),
            metadata = """<meta property="rendition:layout">pre-paginated</meta>""",
        )
    }

    /** One section with a link out and a `javascript:` link, each large enough to touch on screen. */
    fun links(): ByteArray {
        val body = listOf(
            """<p style="font-size: 3em"><a id="out" href="https://exemplo.invalid/real">Saída real</a></p>""",
            """<p style="font-size: 3em"><a id="trap" href="javascript:alert(1)">Armadilha</a></p>""",
        ).joinToString("\n")
        val section = Section("s1.xhtml", "Links", body)
        return epub3("Livro de links", listOf(section), listOf(TocItem(section.title, section.file)))
    }

    /**
     * Three sections in which every word is unique, so the words on screen say which page it is. The
     * second opens with an empty anchor written `<a id="x"/>` and a table without `tbody`, which the
     * frame's HTML parser builds otherwise than an XHTML parser does.
     */
    fun positions(): ByteArray {
        val sections = (1..3).map { number ->
            val start = if (number == 2) "<a id=\"x\"/>\n<table><tr><td>Tabela</td><td>sem corpo</td></tr></table>\n" else ""
            Section("s$number.xhtml", "Seção $number", start + numberedParts(number, "Parte", "parte-$number-", 0 until 3, 10))
        }
        return epub3("Livro de posições", sections, sections.map { TocItem(it.title, it.file) })
    }

    /** One spine item above the paginator's 512 KiB threshold, so it renders in chunks, every word unique. */
    fun positionsCompiled(): ByteArray {
        val chapters = 1..40
        val section = Section("s1.xhtml", "Novela de posições", numberedParts(1, "Capítulo", "cap-", chapters, 42))
        return epub3("Novela de posições", listOf(section), chapters.map { TocItem("Capítulo $it", "s1.xhtml#cap-$it") })
    }

    /** Parts headed `<h2 id="<idPrefix><part>">`, of paragraphs whose words `m<seed>n<count>` never repeat. */
    private fun numberedParts(seed: Int, label: String, idPrefix: String, parts: IntRange, paragraphsPerPart: Int): String {
        var word = 0
        return parts.joinToString("\n") { part ->
            val paragraphs = (1..paragraphsPerPart).joinToString("\n") {
                "<p>" + (1..WORDS_PER_PARAGRAPH).joinToString(" ") { "m${seed}n${++word}" } + "</p>"
            }
            """<h2 id="$idPrefix$part">$label $part</h2>""" + "\n" + paragraphs
        }
    }

    private const val WORDS_PER_PARAGRAPH = 40

    /** Attacks that need a script to run; each one leaves a trace named after it. */
    val HOSTILE_SCRIPTS = listOf("inline-script", "onerror", "svg-script", "book-script", "nested-frame", "reader-script")

    /** Attacks that only need the page to load something it should not. */
    val HOSTILE_REQUESTS = listOf("meta-refresh", "base", "object", "remote-image", "remote-style")

    /**
     * One attack per section, in the order of [HOSTILE_SCRIPTS] and then [HOSTILE_REQUESTS]. A script
     * that runs calls the main page's bridge, renames the main page and asks for `PWNED-<case>`;
     * `reader-script` runs the app's own reader inside the book, so it shows as a second reader.
     */
    fun hostile(): ByteArray {
        val bodies = mapOf(
            "inline-script" to "<script>${sentinel("inline-script")}</script>",
            "onerror" to """<img src="missing.png" onerror="${sentinel("onerror")}"/>""",
            "svg-script" to """<svg xmlns="http://www.w3.org/2000/svg"><script>${sentinel("svg-script")}</script></svg>""",
            "book-script" to """<script src="evil.js"></script>""",
            "nested-frame" to """<iframe srcdoc="&lt;script&gt;${sentinel("nested-frame")}&lt;/script&gt;"></iframe>""",
            "reader-script" to """<script type="module" src="/reader/reader.js"></script>""",
            "meta-refresh" to "",
            "base" to """<img src="PWNED-base.png"/>""",
            "object" to """<object data="PWNED-object"></object>""",
            "remote-image" to """<img src="https://exemplo.invalid/PWNED-remote-image.png"/>""",
            "remote-style" to "",
        )
        val heads = mapOf(
            "meta-refresh" to """<meta http-equiv="refresh" content="0;url=PWNED-meta-refresh"/>""",
            "base" to """<base href="https://exemplo.invalid/"/>""",
            "remote-style" to """<link rel="stylesheet" href="https://exemplo.invalid/PWNED-remote-style.css"/>""",
        )
        val sections = (HOSTILE_SCRIPTS + HOSTILE_REQUESTS).withIndex().map { (index, case) ->
            Section("s${index + 1}.xhtml", "Caso $case", "<p>Texto comum do caso $case.</p>\n" + bodies.getValue(case), heads[case].orEmpty())
        }
        val manifest = """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" + "\n" +
            """<item id="evil" href="evil.js" media-type="application/javascript"/>""" + "\n" + sectionItems(sections)
        val modified = """<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>"""
        return zipped(
            sections,
            PACKAGE to packageDocument("3.0", "Livro hostil", modified, manifest, "<spine>", sections),
            "OEBPS/nav.xhtml" to nav(sections.map { TocItem(it.title, it.file) }),
            "OEBPS/evil.js" to sentinel("book-script"),
        )
    }

    // Single quotes only and no `<` or `&`: it sits inside XHTML, in elements and in attributes.
    private fun sentinel(case: String) = listOf(
        "top.kamayoqBridge.postMessage(JSON.stringify({ type: 'failed', code: 'pwned-$case' }))",
        "top.document.title = 'PWNED-$case'",
        "fetch('PWNED-$case')",
    ).joinToString(" ") { "try { $it } catch (e) {}" }

    private class Section(val file: String, val title: String, val body: String, val head: String = "")
    private class TocItem(val label: String, val href: String)

    private fun epub3(title: String, sections: List<Section>, toc: List<TocItem>, metadata: String = ""): ByteArray {
        val manifest = """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" + "\n" + sectionItems(sections)
        val modified = """<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>""" + metadata
        return zipped(
            sections,
            PACKAGE to packageDocument("3.0", title, modified, manifest, "<spine>", sections),
            "OEBPS/nav.xhtml" to nav(toc),
        )
    }

    private fun sectionItems(sections: List<Section>) = sections.withIndex().joinToString("\n") { (index, section) ->
        """<item id="item$index" href="${section.file}" media-type="application/xhtml+xml"/>"""
    }

    private fun packageDocument(
        version: String,
        title: String,
        metadata: String,
        manifest: String,
        spine: String,
        sections: List<Section>,
    ): String {
        val references = sections.indices.joinToString("\n") { """<itemref idref="item$it"/>""" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="id">urn:kamayoq:fixture:$title</dc:identifier>
            <dc:title>$title</dc:title>
            <dc:language>pt-BR</dc:language>
            $metadata
            </metadata>
            <manifest>
            $manifest
            </manifest>
            $spine
            $references
            </spine>
            </package>
        """.flat()
    }

    private fun nav(toc: List<TocItem>): String {
        val items = toc.joinToString("\n") { """<li><a href="${it.href}">${it.label}</a></li>""" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><title>Sumário</title></head>
            <body><nav epub:type="toc"><ol>
            $items
            </ol></nav></body>
            </html>
        """.flat()
    }

    private fun ncx(sections: List<Section>): String {
        val points = sections.withIndex().joinToString("\n") { (index, section) ->
            """<navPoint id="p$index" playOrder="${index + 1}"><navLabel><text>${section.title}</text></navLabel><content src="${section.file}"/></navPoint>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <head/>
            <docTitle><text>Livro antigo</text></docTitle>
            <navMap>
            $points
            </navMap>
            </ncx>
        """.flat()
    }

    private fun section(section: Section) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>${section.title}</title>${section.head}</head>
        <body><h1>${section.title}</h1>
        ${section.body}
        </body>
        </html>
    """.flat()

    // Not trimIndent: it measures after multi-line text is inserted, and would leave spaces before
    // `<?xml`, which makes the document malformed.
    private fun String.flat() = trim().lines().joinToString("\n") { it.trimStart() }

    private const val PARAGRAPH_BYTES = 1_000

    /** Distinct text per paragraph, so a position can be told from the words on screen. */
    private fun paragraphs(seed: Int, count: Int) = (1..count).joinToString("\n") { number ->
        val sentence = "O faroleiro $seed anotou a maré $number antes de acender a lâmpada do cais. "
        "<p>" + sentence.repeat(PARAGRAPH_BYTES / sentence.length + 1).take(PARAGRAPH_BYTES) + "</p>"
    }

    private fun zipped(sections: List<Section>, vararg documents: Pair<String, String>): ByteArray {
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="$PACKAGE" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.flat()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putStored("mimetype", "application/epub+zip".encodeToByteArray())
            zip.putDeflated("META-INF/container.xml", container)
            for ((name, text) in documents) zip.putDeflated(name, text)
            for (item in sections) zip.putDeflated("OEBPS/${item.file}", section(item))
        }
        return bytes.toByteArray()
    }

    private fun ZipOutputStream.putDeflated(name: String, text: String) {
        putNextEntry(ZipEntry(name).apply { time = ENTRY_TIME })
        write(text.encodeToByteArray())
        closeEntry()
    }

    // The EPUB format wants the first entry uncompressed, so readers can sniff it at a fixed offset.
    private fun ZipOutputStream.putStored(name: String, content: ByteArray) {
        val entry = ZipEntry(name).apply {
            time = ENTRY_TIME
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = CRC32().apply { update(content) }.value
        }
        putNextEntry(entry)
        write(content)
        closeEntry()
    }
}
