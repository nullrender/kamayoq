package app.kamayoq.core.library

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContentCheckTest {

    private val archive = ReadingUnit("X/Cap. 1.cbz", "Cap. 1", UnitKind.CHAPTER, 1.0, UnitContent.Archive)

    private val folder = ReadingUnit(
        "X/Cap. 2",
        "Cap. 2",
        UnitKind.CHAPTER,
        2.0,
        UnitContent.ImageFolder(listOf("X/Cap. 2/01.jpg", "X/Cap. 2/02.jpg")),
    )

    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `dado uma pasta de imagens com as páginas renomeadas, então a impressão muda mesmo com o mesmo tamanho e data`() {
        val stamps = mapOf("X/Cap. 2/01.jpg" to FileStamp(10, 5), "X/Cap. 2/02.jpg" to FileStamp(10, 5))
        val renamed = folder.copy(content = UnitContent.ImageFolder(listOf("X/Cap. 2/a.jpg", "X/Cap. 2/b.jpg")))
        val stampsRenamed = mapOf("X/Cap. 2/a.jpg" to FileStamp(10, 5), "X/Cap. 2/b.jpg" to FileStamp(10, 5))

        // The saved position would fall on another page.
        assertNotEquals(ContentCheck.stampOf(folder, stamps), ContentCheck.stampOf(renamed, stampsRenamed))
    }

    @Test
    fun `dado a mesma pasta de imagens em caminhos que só diferem na maiúscula, então o resumo é o mesmo`() {
        // Letter case alone in the path is not new content.
        val before = ContentCheck.hashOfFolder(listOf("comics/x/Cap. 1/01.jpg")) { bytes("página") }
        val after = ContentCheck.hashOfFolder(listOf("Comics/X/Cap. 1/01.jpg")) { bytes("página") }

        assertEquals(before, after)
    }

    @Test
    fun `dado um arquivo, quando muda o tamanho ou a data, então a impressão muda`() {
        val stamp = ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_000, 5)))

        assertEquals(stamp, ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_000, 5))))
        assertNotEquals(stamp, ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_001, 5))))
        assertNotEquals(stamp, ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_000, 6))))
    }

    @Test
    fun `dado um arquivo sem data, então a impressão vale e difere da de um arquivo com data`() {
        val withoutDate = ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_000, null)))

        assertNotEquals(withoutDate, ContentCheck.stampOf(archive, mapOf(archive.path to FileStamp(1_000, 0))))
    }

    @Test
    fun `dado uma pasta de imagens, quando uma imagem muda de tamanho ou de data, então a impressão muda`() {
        val pages = mapOf("X/Cap. 2/01.jpg" to FileStamp(300, 5), "X/Cap. 2/02.jpg" to FileStamp(400, 7))
        val stamp = ContentCheck.stampOf(folder, pages)

        assertNotEquals(stamp, ContentCheck.stampOf(folder, pages + ("X/Cap. 2/02.jpg" to FileStamp(401, 7))))
        assertNotEquals(stamp, ContentCheck.stampOf(folder, pages + ("X/Cap. 2/02.jpg" to FileStamp(400, 8))))
    }

    @Test
    fun `dado um arquivo sem impressão na lista, então é recusado`() {
        assertFailsWith<IllegalArgumentException> { ContentCheck.stampOf(archive, emptyMap()) }
    }

    @Test
    fun `dado um conteúdo conhecido, então o resumo é o SHA-256 dele`() {
        // The SHA-256 of "abc" from FIPS 180-2, appendix B.1.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentCheck.hashOfFile(bytes("abc")),
        )
    }

    @Test
    fun `dado dois arquivos de conteúdo igual, então o resumo é igual, e de conteúdo diferente, difere`() {
        assertEquals(ContentCheck.hashOfFile(bytes("page one")), ContentCheck.hashOfFile(bytes("page one")))
        assertNotEquals(ContentCheck.hashOfFile(bytes("page one")), ContentCheck.hashOfFile(bytes("page two")))
    }

    @Test
    fun `dado uma pasta de imagens, quando duas páginas trocam de conteúdo entre si, então o resumo muda`() {
        val pages = listOf("01.jpg", "02.jpg")
        val original = mapOf("01.jpg" to "first", "02.jpg" to "second")
        val swapped = mapOf("01.jpg" to "second", "02.jpg" to "first")

        assertNotEquals(
            ContentCheck.hashOfFolder(pages) { bytes(original.getValue(it)) },
            ContentCheck.hashOfFolder(pages) { bytes(swapped.getValue(it)) },
        )
    }

    @Test
    fun `dado uma pasta de imagens, quando o conteúdo de uma página passa para o nome da outra, então o resumo muda`() {
        // Without a boundary between name and content, "a" + "bc" would hash like "ab" + "c".
        assertNotEquals(
            ContentCheck.hashOfFolder(listOf("a")) { bytes("bc") },
            ContentCheck.hashOfFolder(listOf("ab")) { bytes("c") },
        )
    }
}
