package app.kamayoq.core.kavita

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KavitaTranslationTest {
    private val series = RemoteWork(12, "Sakamoto Days", 2)

    private fun chapter(
        id: Int = 1,
        number: Double = 1.0,
        volume: Double = -100000.0,
        special: Boolean = false,
        title: String = "",
        pages: Int = 3,
        files: List<RemoteFile> = listOf(RemoteFile("/manga/Obra/Cap. $id.cbz", 1, 100)),
    ) = RemoteChapter(id, number, number, pages, volumeId = 7, volumeNumber = volume, isSpecial = special, titleName = title, files = files)

    private fun work(type: Int, vararg chapters: RemoteChapter) =
        KavitaTranslation.work(RemoteLibrary(2, "Biblioteca", type), series, chapters.toList())

    @Test
    fun `dado o número -100000, então o número é desconhecido`() {
        assertNull(work(0, chapter(number = -100000.0)).chapters.single().number)
    }

    @Test
    fun `dado o capítulo 12,5, então o número é 12,5`() {
        assertEquals(12.5, work(0, chapter(number = 12.5)).chapters.single().number)
    }

    @Test
    fun `dado cada tipo de biblioteca do Kavita, então a obra ganha o tipo do Kamayoq`() {
        val types = (0..5).map { type -> work(type).let { it.type to it.subtype } }

        assertEquals(
            listOf(
                WorkType.COMICS to ComicsSubtype.MANGA,
                WorkType.COMICS to null,
                WorkType.BOOKS to null,
                WorkType.COMICS to null,
                WorkType.NOVELS to null,
                WorkType.COMICS to null,
            ),
            types,
        )
    }

    @Test
    fun `dado um tipo de biblioteca que este app não conhece, então a tradução recusa`() {
        assertFailsWith<IllegalArgumentException> { work(99) }
    }

    @Test
    fun `dado volumes, soltos e especial, então a ordem é volumes, soltos, especiais`() {
        val translated = work(
            0,
            chapter(id = 1, number = 5.0),
            chapter(id = 2, number = 1.0, volume = 1.0),
            chapter(id = 3, number = 1.0, volume = 2.0),
            chapter(id = 4, number = -100000.0, volume = 100000.0, special = true),
        )

        assertEquals(listOf("2", "3", "1", "4"), translated.chapters.map { it.path })
    }

    @Test
    fun `dado um título do Kavita, então ele é o nome do capítulo`() {
        assertEquals("Saga book 1", work(2, chapter(title = "Saga book 1")).chapters.single().name)
    }

    @Test
    fun `dado um capítulo sem título, então o nome é o do arquivo sem a extensão`() {
        assertEquals("Cap. 1", work(0, chapter()).chapters.single().name)
    }

    @Test
    fun `dado um livro de volume numerado sem número de capítulo, então ele é um volume`() {
        val book = work(2, chapter(number = -100000.0, volume = 3.0)).chapters.single()

        assertEquals(UnitKind.VOLUME, book.kind)
    }

    @Test
    fun `dado um capítulo solto, então ele é um capítulo`() {
        assertEquals(UnitKind.CHAPTER, work(0, chapter()).chapters.single().kind)
    }

    @Test
    fun `dado cada arquivo, então o formato diz o conteúdo`() {
        val formatOf = { path: String, kind: Int ->
            work(0, chapter(files = listOf(RemoteFile(path, kind, 1)))).chapters.single().format
        }

        assertEquals(ChapterFormat.ARCHIVE, formatOf("/m/a.cbz", 1))
        assertEquals(ChapterFormat.ARCHIVE, formatOf("/m/a.ZIP", 1))
        assertEquals(ChapterFormat.OTHER_ARCHIVE, formatOf("/m/a.cbr", 1))
        assertEquals(ChapterFormat.EPUB, formatOf("/m/a.epub", 3))
        assertEquals(ChapterFormat.PDF, formatOf("/m/a.pdf", 4))
    }

    @Test
    fun `dado um capítulo com dois arquivos, então o formato é outro e o nome guardado é o menor`() {
        val files = listOf(RemoteFile("/m/Obra/b.cbz", 1, 10), RemoteFile("/m/Outra/a.cbz", 1, 20))

        val translated = work(0, chapter(files = files)).chapters.single()

        assertEquals(ChapterFormat.OTHER_ARCHIVE, translated.format)
        assertEquals("a.cbz", translated.remoteFile)
    }

    @Test
    fun `dado um servidor Windows, então o nome do arquivo sai sem a pasta`() {
        val translated = work(0, chapter(files = listOf(RemoteFile("C:\\Manga\\Obra\\Cap. 1.cbz", 1, 1)))).chapters.single()

        assertEquals("Cap. 1.cbz", translated.remoteFile)
    }

    @Test
    fun `dado páginas e bytes, então o carimbo muda quando o arquivo troca e não com a data`() {
        val first = work(0, chapter(pages = 3, files = listOf(RemoteFile("/m/a.cbz", 1, 499)))).chapters.single()
        val swapped = work(0, chapter(pages = 3, files = listOf(RemoteFile("/m/a.cbz", 1, 1091)))).chapters.single()

        assertEquals("3|499", first.stamp)
        assertEquals("3|1091", swapped.stamp)
    }

    @Test
    fun `dado o capítulo traduzido, então guarda o que o envio ao Kavita precisa`() {
        val translated = work(0, chapter(id = 91)).chapters.single()

        assertEquals("91", translated.path)
        assertEquals(7, translated.remoteVolumeId)
        assertEquals(3, translated.pageCount)
    }
}
