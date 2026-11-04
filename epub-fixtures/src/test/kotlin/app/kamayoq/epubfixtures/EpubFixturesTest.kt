package app.kamayoq.epubfixtures

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.xml.sax.InputSource

class EpubFixturesTest {

    private class Entry(val zip: ZipEntry, val text: String)

    private fun entriesOf(epub: ByteArray): List<Entry> {
        val entries = mutableListOf<Entry>()
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += Entry(entry, zip.readBytes().decodeToString())
            }
        }
        return entries
    }

    private fun List<Entry>.text(name: String) = single { it.zip.name == name }.text

    private val allEpubs =
        listOf(
            EpubFixtures.short(),
            EpubFixtures.compiled(),
            EpubFixtures.epub2WithNcx(),
            EpubFixtures.hostile(),
            EpubFixtures.links(),
            EpubFixtures.fixedLayout(),
            EpubFixtures.positions(),
            EpubFixtures.positionsCompiled(),
        )

    @Test
    fun `dado o gerador, quando crio um EPUB, então mimetype é a primeira entrada, sem compressão`() {
        for (epub in allEpubs) {
            val first = entriesOf(epub).first()
            assertEquals("mimetype", first.zip.name)
            assertEquals(ZipEntry.STORED, first.zip.method)
            assertEquals("application/epub+zip", first.text)
        }
    }

    @Test
    fun `dado o gerador, quando crio um EPUB 3, então o pacote tem exatamente um nav e um dcterms modified`() {
        for (epub in listOf(EpubFixtures.short(), EpubFixtures.compiled())) {
            val opf = entriesOf(epub).text("OEBPS/content.opf")
            assertEquals(1, Regex("""properties="nav"""").findAll(opf).count())
            assertEquals(1, Regex("""property="dcterms:modified"""").findAll(opf).count())
        }
    }

    @Test
    fun `dado qualquer EPUB gerado, então o container aponta para o pacote`() {
        for (epub in allEpubs) {
            assertContains(entriesOf(epub).text("META-INF/container.xml"), """full-path="OEBPS/content.opf"""")
        }
    }

    @Test
    fun `dado o EPUB curto, então ele tem três seções, com cada uma no spine e no sumário`() {
        val entries = entriesOf(EpubFixtures.short())
        val opf = entries.text("OEBPS/content.opf")

        assertEquals(3, Regex("<itemref ").findAll(opf).count())
        assertEquals(3, Regex("<li>").findAll(entries.text("OEBPS/nav.xhtml")).count())
        assertContains(entries.text("OEBPS/s2.xhtml"), "Seção 2")
    }

    @Test
    fun `dado o EPUB compilado, então é um spine só acima de 512 KiB, com capítulos no sumário por âncora`() {
        val entries = entriesOf(EpubFixtures.compiled())
        val section = entries.text("OEBPS/s1.xhtml")

        assertEquals(1, Regex("<itemref ").findAll(entries.text("OEBPS/content.opf")).count())
        assertTrue(section.encodeToByteArray().size > 512 * 1024)
        assertContains(entries.text("OEBPS/nav.xhtml"), """href="s1.xhtml#cap-2"""")
        assertContains(section, """id="cap-2"""")
    }

    @Test
    fun `dado o EPUB 2, então o sumário vem do NCX e não há nav`() {
        val entries = entriesOf(EpubFixtures.epub2WithNcx())
        val opf = entries.text("OEBPS/content.opf")

        assertContains(opf, """version="2.0"""")
        assertContains(opf, """<spine toc="ncx">""")
        assertContains(entries.text("OEBPS/toc.ncx"), "<navPoint ")
        assertFalse("""properties="nav"""" in opf)
    }

    @Test
    fun `dado qualquer EPUB gerado, então todo XML dele é bem formado`() {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        for (epub in allEpubs) {
            for (entry in entriesOf(epub).filter { it.zip.name.substringAfterLast('.') in setOf("xml", "opf", "xhtml", "ncx") }) {
                parser.parse(InputSource(StringReader(entry.text)))
            }
        }
    }

    @Test
    fun `dado o livro hostil, então cada ataque tem a sua seção, na ordem das listas`() {
        val entries = entriesOf(EpubFixtures.hostile())
        val cases = EpubFixtures.HOSTILE_SCRIPTS + EpubFixtures.HOSTILE_REQUESTS

        assertEquals(cases.size, Regex("<itemref ").findAll(entries.text("OEBPS/content.opf")).count())
        for ((index, case) in cases.withIndex()) assertContains(entries.text("OEBPS/s${index + 1}.xhtml"), "Caso $case")
        assertContains(entries.text("OEBPS/evil.js"), "pwned-book-script")
    }

    // Piece 14 tells a page by the words on it: a word seen twice in the book could match the wrong page.
    @Test
    fun `dado os livros de posição, então cada palavra do texto aparece uma vez só no livro`() {
        for (epub in listOf(EpubFixtures.positions(), EpubFixtures.positionsCompiled())) {
            val words = entriesOf(epub)
                .filter { it.zip.name.matches(Regex("""OEBPS/s\d+\.xhtml""")) }
                .flatMap { entry -> Regex("""m\d+n\d+""").findAll(entry.text).map { it.value }.toList() }

            assertTrue(words.size > 1_000, "only ${words.size} words")
            assertEquals(words.size, words.toSet().size)
        }
    }

    @Test
    fun `dado o livro de posição, então a segunda seção abre com âncora vazia e tabela sem tbody`() {
        val section = entriesOf(EpubFixtures.positions()).text("OEBPS/s2.xhtml")

        assertContains(section, """<a id="x"/>""")
        assertContains(section, "<table><tr>")
        assertFalse("tbody" in section)
        assertContains(section, """id="parte-2-1"""")
    }

    @Test
    fun `dado o livro de posição compilado, então é um spine só acima do limite de pedaços do paginator`() {
        val entries = entriesOf(EpubFixtures.positionsCompiled())

        assertEquals(1, Regex("<itemref ").findAll(entries.text("OEBPS/content.opf")).count())
        assertTrue(entries.text("OEBPS/s1.xhtml").encodeToByteArray().size > 512 * 1024)
        assertContains(entries.text("OEBPS/s1.xhtml"), """id="cap-30"""")
    }

    @Test
    fun `dado o gerador, quando crio duas vezes, então os bytes são iguais`() {
        assertContentEquals(EpubFixtures.short(), EpubFixtures.short())
    }
}
