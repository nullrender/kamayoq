package app.kamayoq.core.reader

import app.kamayoq.core.library.ContentCheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchivePagesTest {

    /** A small CBZ made here: each entry's content is its own name, so pages are easy to tell apart. */
    private fun cbz(vararg names: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Copies the archive to a file, as the app does to its cache, and opens the copy. */
    private fun <T> opened(archive: ByteArray, read: (ArchiveChapter) -> T): T {
        val copy = File.createTempFile("chapter", ".cbz")
        try {
            copy.outputStream().use { ArchivePages.copyHashing(ByteArrayInputStream(archive), it) }
            return ArchivePages.open(copy).use(read)
        } finally {
            copy.delete()
        }
    }

    /** The content of each page, in reading order. */
    private fun pagesOf(archive: ByteArray): List<String> =
        opened(archive) { chapter -> chapter.pages.map { chapter.open(it).use { page -> page.readBytes().decodeToString() } } }

    @Test
    fun `dado um CBZ com as imagens fora de ordem, então as páginas saem em ordem natural`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), pagesOf(cbz("10.jpg", "1.jpg", "2.jpg")))
    }

    @Test
    fun `dado um CBZ com ComicInfo, pasta do macOS, arquivo oculto e texto, então só as imagens viram página`() {
        val archive = cbz("ComicInfo.xml", "__MACOSX/._01.jpg", ".hidden.png", "notes.txt", "01.jpg", "02.webp")

        assertEquals(listOf("01.jpg", "02.webp"), pagesOf(archive))
    }

    @Test
    fun `dado um CBZ com as imagens numa pasta interna, então elas viram página`() {
        assertEquals(
            listOf("Cap 1/01.png", "Cap 1/02.png"),
            pagesOf(cbz("Cap 1/", "Cap 1/02.png", "Cap 1/01.png")),
        )
    }

    @Test
    fun `dado nomes que apontam para fora da pasta, então a chave de cada página é feita pelo app`() {
        val keys = opened(cbz("../../evil.jpg", "/abs.jpg")) { it.pages }

        // ".." starts with a dot, so the entry under it is skipped like a hidden folder.
        assertEquals(listOf("page0"), keys)
        keys.forEach { assertEquals(true, it.none { char -> char == '/' || char == '.' }, "unsafe key: $it") }
    }

    @Test
    fun `dado um CBZ copiado, então a cópia é igual e o resumo é o mesmo do arquivo inteiro`() {
        val archive = cbz("01.jpg", "02.jpg", "ComicInfo.xml")
        val copy = ByteArrayOutputStream()

        val hash = ArchivePages.copyHashing(ByteArrayInputStream(archive), copy)

        assertContentEquals(archive, copy.toByteArray())
        assertEquals(ContentCheck.hashOfFile(ByteArrayInputStream(archive)), hash)
    }

    /**
     * Made once by Python's zipfile writing to a pipe, as Mihon's downloads are found on the phone
     * (test of 25/10/2026): entries stored without compression, each with size 0 in its local
     * header and the real size in a data descriptor after it; Mihon's names ("<page>__<part>.jpg"),
     * out of order, with ComicInfo.xml last. Content of each entry: its name.
     */
    private fun mihonCbz(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mihon-stored-descriptor.cbz")).use { it.readBytes() }

    @Test
    fun `dado o arquivo de teste do Mihon, então ele tem o formato que o ZipInputStream não lê`() {
        val header = ByteBuffer.wrap(mihonCbz()).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0x04034b50, header.getInt(0), "local header signature")
        assertEquals(0x08, header.getShort(6).toInt() and 0x08, "data descriptor flag")
        assertEquals(0, header.getShort(8).toInt(), "stored, no compression")
        assertEquals(0, header.getInt(18), "compressed size in the local header")
    }

    @Test
    fun `dado um CBZ do Mihon, sem compressão e com o tamanho depois de cada imagem, então todas as páginas saem`() {
        assertEquals(
            listOf("001__001.jpg", "001__002.jpg", "002__001.jpg", "010__001.jpg"),
            pagesOf(mihonCbz()),
        )
    }

    @Test
    fun `dado um CBZ sem imagens, então é recusado`() {
        assertFailsWith<IllegalArgumentException> {
            pagesOf(cbz("ComicInfo.xml", "notes.txt"))
        }
    }

    @Test
    fun `dado um arquivo que não é ZIP, então é recusado`() {
        assertFailsWith<IllegalArgumentException> {
            pagesOf("not a zip at all".toByteArray())
        }
    }
}
