package app.kamayoq.core.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubArchiveTest {

    private val container = "META-INF/container.xml" to "<container/>"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun <T> opened(archive: ByteArray, limits: EpubLimits = EpubLimits(), read: (EpubArchive) -> T): T {
        val copy = File.createTempFile("book", ".epub")
        try {
            copy.writeBytes(archive)
            return EpubArchive.open(copy, limits).use(read)
        } finally {
            copy.delete()
        }
    }

    /** Rewrites the size that the index at the end of the ZIP declares for every entry. */
    private fun withDeclaredSize(archive: ByteArray, size: Int): ByteArray {
        val buffer = ByteBuffer.wrap(archive.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0..archive.size - 46) {
            if (buffer.getInt(offset) == CENTRAL_HEADER) buffer.putInt(offset + 24, size)
        }
        return buffer.array()
    }

    /** Rewrites the compression method that the index declares for every entry (12 is bzip2). */
    private fun withMethod(archive: ByteArray, method: Short): ByteArray {
        val buffer = ByteBuffer.wrap(archive.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0..archive.size - 46) {
            if (buffer.getInt(offset) == CENTRAL_HEADER) buffer.putShort(offset + 10, method)
        }
        return buffer.array()
    }

    // Such an entry opened fine and then threw inside the
    // WebView's request thread, which ends the app.
    @Test
    fun `dado uma entrada com método de compressão que o leitor não conhece, então o livro é recusado ao abrir`() {
        assertFailsWith<IllegalArgumentException> { opened(withMethod(zip(container, "a.xhtml" to "x"), 12)) {} }
    }

    @Test
    fun `dado um EPUB, quando abro, então cada entrada sai igual ao que foi escrito`() {
        opened(zip(container, "OEBPS/a.xhtml" to "primeira", "OEBPS/b.xhtml" to "segunda")) { book ->
            assertEquals(setOf("META-INF/container.xml", "OEBPS/a.xhtml", "OEBPS/b.xhtml"), book.names)
            assertEquals("segunda", book.open("OEBPS/b.xhtml").use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun `dado uma entrada, então o tamanho dela é conhecido sem ler`() {
        opened(zip(container, "a.xhtml" to "12345")) { book -> assertEquals(5L, book.sizeOf("a.xhtml")) }
    }

    @Test
    fun `dado uma pasta dentro do ZIP, então ela não é entrada`() {
        opened(zip(container, "OEBPS/" to "")) { book -> assertFalse("OEBPS/" in book.names) }
    }

    @Test
    fun `dado uma entrada cujo cabeçalho mente o tamanho, então a leitura para no limite`() {
        val lying = withDeclaredSize(zip(container, "a.xhtml" to "x".repeat(100_000)), 12)

        opened(lying) { book ->
            val read = ByteArrayOutputStream()
            assertFailsWith<IOException> { book.open("a.xhtml").use { it.copyTo(read) } }
            assertTrue(read.size() <= 12, "Li ${read.size()} bytes")
        }
    }

    @Test
    fun `dado uma entrada maior que o limite, então o livro é recusado`() {
        val big = zip(container, "a.xhtml" to "x".repeat(1_000))

        assertFailsWith<IllegalArgumentException> { opened(big, EpubLimits(maxEntryBytes = 999)) {} }
    }

    @Test
    fun `dado a soma das entradas acima do limite, então o livro é recusado`() {
        val big = zip(container, "a.xhtml" to "x".repeat(600), "b.xhtml" to "y".repeat(600))

        assertFailsWith<IllegalArgumentException> { opened(big, EpubLimits(maxTotalBytes = 1_000)) {} }
    }

    @Test
    fun `dado entradas demais, então o livro é recusado`() {
        val many = zip(container, "a.xhtml" to "", "b.xhtml" to "", "c.xhtml" to "")

        assertFailsWith<IllegalArgumentException> { opened(many, EpubLimits(maxEntries = 3)) {} }
    }

    @Test
    fun `dado um ZIP com nome repetido, então é recusado`() {
        val twice = String(zip(container, "a.xhtml" to "boa", "b.xhtml" to "ruim"), Charsets.ISO_8859_1)
            .replace("b.xhtml", "a.xhtml")
            .toByteArray(Charsets.ISO_8859_1)

        assertFailsWith<IllegalArgumentException> { opened(twice) {} }
    }

    @Test
    fun `dado um nome com pontos, barra invertida ou caminho absoluto, então não há entrada`() {
        val tricks = listOf("../fora.xhtml", "OEBPS/../../fora.xhtml", "/data/fora.xhtml", "OEBPS\\fora.xhtml", "./a.xhtml")

        opened(zip(container, *tricks.map { it to "x" }.toTypedArray(), "OEBPS/boa.xhtml" to "y")) { book ->
            assertEquals(setOf("META-INF/container.xml", "OEBPS/boa.xhtml"), book.names)
        }
    }

    @Test
    fun `dado um nome fora do mapa, então pedir a entrada falha em vez de abrir outra coisa`() {
        opened(zip(container)) { book -> assertFailsWith<NoSuchElementException> { book.open("../segredo") } }
    }

    @Test
    fun `dado um arquivo que não é ZIP, então é recusado`() {
        assertFailsWith<IllegalArgumentException> { opened("não sou um ZIP".toByteArray()) {} }
    }

    @Test
    fun `dado um ZIP sem container xml, então é recusado`() {
        assertFailsWith<IllegalArgumentException> { opened(zip("a.xhtml" to "x")) {} }
    }

    private companion object {
        const val CENTRAL_HEADER = 0x02014b50
    }
}
