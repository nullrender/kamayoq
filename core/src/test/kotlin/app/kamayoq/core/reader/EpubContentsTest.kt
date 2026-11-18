package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubContentsTest {
    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun opf(files: List<String>, manifestExtra: String = "", spine: String = "<spine>") = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <manifest>
        $manifestExtra
        ${files.withIndex().joinToString("\n") { (index, file) -> """<item id="i$index" href="$file" media-type="application/xhtml+xml"/>""" }}
        </manifest>
        $spine
        ${files.indices.joinToString("\n") { """<itemref idref="i$it"/>""" }}
        </spine>
        </package>
    """.trimIndent()

    private val navItem = """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""

    private fun nav(list: String) = """
        <?xml version="1.0"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <body><nav epub:type="toc"><ol>
        $list
        </ol></nav></body>
        </html>
    """.trimIndent()

    private fun book(vararg documents: Pair<String, String>): BookContents {
        val entries = mapOf("META-INF/container.xml" to container) + documents
        return EpubContents.read(entries::get)
    }

    private fun epub3(files: List<String>, list: String) =
        book("OEBPS/content.opf" to opf(files, navItem), "OEBPS/nav.xhtml" to nav(list))

    @Test
    fun `dado um sumário de 3 itens, então há 3 capítulos na ordem do sumário`() {
        val contents = epub3(
            listOf("c1.xhtml", "c2.xhtml", "c3.xhtml"),
            """
            <li><a href="c1.xhtml">Cap. 1</a></li>
            <li><a href="c2.xhtml">Cap. 2</a></li>
            <li><a href="c3.xhtml">Cap. 3</a></li>
            """,
        )

        assertEquals(
            listOf(
                ContentsChapter("Cap. 1", "OEBPS/c1.xhtml", volume = null),
                ContentsChapter("Cap. 2", "OEBPS/c2.xhtml", volume = null),
                ContentsChapter("Cap. 3", "OEBPS/c3.xhtml", volume = null),
            ),
            contents.chapters,
        )
        assertEquals(listOf(0, 1, 2), contents.chapterOfItem)
    }

    @Test
    fun `dado Volume 1 com Cap 1 e Cap 2, então um volume com 2 capítulos`() {
        val contents = epub3(
            listOf("v1.xhtml", "c1.xhtml", "c2.xhtml"),
            """
            <li><a href="v1.xhtml">Volume 1</a><ol>
            <li><a href="c1.xhtml">Cap. 1</a></li>
            <li><a href="c2.xhtml">Cap. 2</a></li>
            </ol></li>
            """,
        )

        assertEquals(
            listOf(
                ContentsChapter("Cap. 1", "OEBPS/c1.xhtml", volume = "Volume 1"),
                ContentsChapter("Cap. 2", "OEBPS/c2.xhtml", volume = "Volume 1"),
            ),
            contents.chapters,
        )
        // The volume's own page leads into its first chapter.
        assertEquals(listOf(0, 0, 1), contents.chapterOfItem)
    }

    @Test
    fun `dado dois itens com o mesmo destino, então 1 capítulo com o primeiro título`() {
        val contents = epub3(
            listOf("c1.xhtml"),
            """
            <li><a href="c1.xhtml#a">Capítulo 1</a></li>
            <li><a href="c1.xhtml#a">O começo</a></li>
            """,
        )

        assertEquals(listOf(ContentsChapter("Capítulo 1", "OEBPS/c1.xhtml#a", volume = null)), contents.chapters)
        assertEquals(listOf(0, 0), contents.chapterOfItem)
    }

    @Test
    fun `dado um sumário vazio, então nenhum capítulo, o arquivo não traz a divisão`() {
        assertEquals(emptyList(), epub3(listOf("c1.xhtml"), "").chapters)
    }

    @Test
    fun `dado um livro sem sumário nenhum, então nenhum capítulo`() {
        assertEquals(emptyList(), book("OEBPS/content.opf" to opf(listOf("c1.xhtml"))).chapters)
    }

    @Test
    fun `dado um sumário malformado, então nenhum capítulo`() {
        val contents = book(
            "OEBPS/content.opf" to opf(listOf("c1.xhtml"), navItem),
            "OEBPS/nav.xhtml" to nav("""<li><a href="c1.xhtml">Cap. 1</li>"""),
        )

        assertEquals(emptyList(), contents.chapters)
    }

    @Test
    fun `dado um item que aponta para fora da lombada ou para fora do livro, então ele não é capítulo`() {
        val contents = epub3(
            listOf("c1.xhtml"),
            """
            <li><a href="https://exemplo.invalid/site">Site</a></li>
            <li><a href="extra.xhtml">Extra</a></li>
            <li><a href="c1.xhtml">Cap. 1</a></li>
            <li><span>Sem destino</span></li>
            """,
        )

        assertEquals(listOf(ContentsChapter("Cap. 1", "OEBPS/c1.xhtml", volume = null)), contents.chapters)
        assertEquals(listOf(0, 0, 0, 0), contents.chapterOfItem)
    }

    @Test
    fun `dado um EPUB 2 com NCX e DOCTYPE, então os capítulos saem do NCX sem buscar a DTD`() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <head/>
            <navMap>
            <navPoint id="p1" playOrder="1"><navLabel><text>Parte  1 &amp; prólogo</text></navLabel><content src="Text/c1.xhtml"/>
              <navPoint id="p2" playOrder="2"><navLabel><text>Cap. 1</text></navLabel><content src="Text/c1.xhtml#cap1"/></navPoint>
            </navPoint>
            <navPoint id="p3" playOrder="3"><navLabel><text>Cap. 2</text></navLabel><content src="Text/c%202.xhtml"/></navPoint>
            </navMap>
            </ncx>
        """.trimIndent()
        val manifest = """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        val contents = book(
            "OEBPS/content.opf" to opf(listOf("Text/c1.xhtml", "Text/c%202.xhtml"), manifest, """<spine toc="ncx">"""),
            "OEBPS/toc.ncx" to ncx,
        )

        assertEquals(
            listOf(
                ContentsChapter("Cap. 1", "OEBPS/Text/c1.xhtml#cap1", volume = "Parte 1 & prólogo"),
                ContentsChapter("Cap. 2", "OEBPS/Text/c 2.xhtml", volume = null),
            ),
            contents.chapters,
        )
    }

    @Test
    fun `dado um nav só com texto sem destino e um NCX, então vale o NCX`() {
        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint><navLabel><text>Cap. 1</text></navLabel><content src="c1.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val manifest = navItem + "\n" + """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        val contents = book(
            "OEBPS/content.opf" to opf(listOf("c1.xhtml"), manifest),
            "OEBPS/nav.xhtml" to nav("<li><span>Cap. 1</span></li>"),
            "OEBPS/toc.ncx" to ncx,
        )

        assertEquals(listOf(ContentsChapter("Cap. 1", "OEBPS/c1.xhtml", volume = null)), contents.chapters)
    }

    @Test
    fun `dado o sumário numa pasta e os capítulos em outra, então a chave é o caminho dentro do EPUB`() {
        val contents = book(
            "OEBPS/content.opf" to opf(
                listOf("Text/c1.xhtml"),
                """<item id="nav" href="Nav/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""",
            ),
            "OEBPS/Nav/nav.xhtml" to nav("""<li><a href="../Text/c1.xhtml?x=1#cap">Cap. 1</a></li>"""),
        )

        assertEquals(listOf(ContentsChapter("Cap. 1", "OEBPS/Text/c1.xhtml#cap", volume = null)), contents.chapters)
    }

    @Test
    fun `dado um título dentro de outros elementos e com quebras de linha, então o título vem limpo`() {
        val contents = epub3(
            listOf("c1.xhtml"),
            "<li><a href=\"c1.xhtml\">\n  <b>Cap.</b>\n  1&#160;— <i>O faroleiro</i>\n</a></li>",
        )

        assertEquals("Cap. 1 — O faroleiro", contents.chapters.single().title)
    }

    @Test
    fun `dado títulos com travessão e reticências escritos por nome, então viram os caracteres, como no leitor`() {
        val contents = epub3(listOf("c1.xhtml"), """<li><a href="c1.xhtml">Cap. 1 &mdash; O cais&HELLIP;</a></li>""")

        assertEquals("Cap. 1 — O cais…", contents.chapters.single().title)
    }

    @Test
    fun `dado um nav com um nome que o XML não conhece e um NCX, então o nav é descartado e vale o NCX`() {
        val ncx = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint><navLabel><text>Do NCX</text></navLabel><content src="c1.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val manifest = navItem + "\n" + """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        val contents = book(
            "OEBPS/content.opf" to opf(listOf("c1.xhtml"), manifest),
            "OEBPS/nav.xhtml" to nav("""<li><a href="c1.xhtml">Pr&eacute;-texto</a></li>"""),
            "OEBPS/toc.ncx" to ncx,
        )

        assertEquals(listOf("Do NCX"), contents.chapters.map { it.title })
    }

    @Test
    fun `dado um destino quebrado em duas linhas no arquivo, então a quebra vale um espaço`() {
        val contents = epub3(listOf("cap 1.xhtml"), "<li><a href=\"cap\n1.xhtml\">Cap. 1</a></li>")

        assertEquals(listOf("OEBPS/cap 1.xhtml"), contents.chapters.map { it.key })
    }

    @Test
    fun `dado um livro sem container legível, então nenhum capítulo`() {
        assertEquals(emptyList(), EpubContents.read { null }.chapters)
        assertEquals(emptyList(), EpubContents.read { "<container>" }.chapters)
    }
}
