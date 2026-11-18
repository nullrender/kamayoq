package app.kamayoq.data

import app.kamayoq.core.reader.ContentsChapter
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.EpubLimits
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Step 4, piece B6: the reading of the folder opens a file to split only to read its contents.
class SplitContentsTest {
    private val cache: File = Files.createTempDirectory("kamayoq-contents").toFile()

    @AfterTest
    fun cleanUp() {
        cache.deleteRecursively()
    }

    private fun read(bytes: ByteArray, limits: EpubLimits = EpubLimits()) = SplitContents.read(ByteArrayInputStream(bytes), cache, limits)

    @Test
    fun `dado o romance em volumes, quando leio o sumário do arquivo, então vêm os 5 capítulos com o volume e o destino`() {
        assertEquals(
            listOf(
                ContentsChapter("Capítulo 1", "OEBPS/v1.xhtml#cap-1", "Volume 1"),
                ContentsChapter("Capítulo 2", "OEBPS/v1.xhtml#cap-2", "Volume 1"),
                ContentsChapter("Capítulo 3", "OEBPS/c3.xhtml", "Volume 1"),
                ContentsChapter("Capítulo 4", "OEBPS/v2.xhtml#cap-4", "Volume 2"),
                ContentsChapter("Capítulo 5", "OEBPS/v2.xhtml#cap-5", "Volume 2"),
            ),
            read(EpubFixtures.novel()),
        )
    }

    @Test
    fun `dado um EPUB 2, quando leio o sumário do arquivo, então os capítulos vêm do NCX`() {
        assertEquals(listOf("Parte 1", "Parte 2"), read(EpubFixtures.epub2WithNcx()).map { it.title })
    }

    @Test
    fun `dado um arquivo lido, então a cópia dele não fica no cache`() {
        read(EpubFixtures.novel())

        assertEquals(emptyList(), cache.listFiles().orEmpty().toList())
    }

    @Test
    fun `dado um arquivo acima do teto, então não há capítulos e nada fica no cache`() {
        assertEquals(emptyList(), read(EpubFixtures.novel(), EpubLimits(maxFileBytes = 1_000)))
        assertEquals(emptyList(), cache.listFiles().orEmpty().toList())
    }

    // Decided on 18/11/2026: a file that does not open as an archive, such as one still being copied,
    // cannot be read now, so the reading of the folder keeps its chapters and tries it again.
    @Test
    fun `dado um arquivo que não abre como ZIP, como um cortado no meio da cópia, então a leitura falha como arquivo que não se lê e nada fica no cache`() {
        val novel = EpubFixtures.novel()

        assertFailsWith<IOException> { read("isto não é um ZIP".encodeToByteArray()) }
        assertFailsWith<IOException> { read(novel.copyOf(novel.size / 2)) }
        assertEquals(emptyList(), cache.listFiles().orEmpty().toList())
    }

    @Test
    fun `dado um livro aberto, então o sumário dele traz os capítulos e a que capítulo cada item leva`() {
        val file = File(cache, "novel.epub").apply { writeBytes(EpubFixtures.novel()) }
        val contents = EpubArchive.open(file).use(SplitContents::of)

        assertEquals(5, contents.chapters.size)
        // Volume 1, its three chapters, Volume 2, its two chapters and the item that repeats chapter 5.
        assertEquals(listOf(0, 0, 1, 2, 3, 3, 4, 4), contents.chapterOfItem)
        assertTrue(file.delete())
    }
}
