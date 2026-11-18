package app.kamayoq.core.library

import app.kamayoq.core.reader.ContentsChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitFilesTest {
    private val compiled = "Novels/Release That Witch/Release That Witch.epub"

    private fun library(vararg units: ReadingUnit) =
        LocalLibrary(listOf(Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units.toList())), emptyList())

    private fun file(path: String = compiled, content: UnitContent = UnitContent.SplitEpub) =
        ReadingUnit(path, path.substringAfterLast('/').substringBeforeLast('.'), UnitKind.CHAPTER, null, content)

    private fun LocalLibrary.units() = works.single().units

    @Test
    fun `dado um EPUB compilado com um sumário de 3 itens, então são 3 capítulos do mesmo arquivo, na ordem do sumário`() {
        val contents = listOf(
            ContentsChapter("Capítulo 1 — A bruxa", "OEBPS/c1.xhtml", volume = null),
            ContentsChapter("Capítulo 2", "OEBPS/c2.xhtml", volume = null),
            ContentsChapter("Epílogo", "OEBPS/c2.xhtml#fim", volume = null),
        )

        val units = SplitFiles.expand(library(file())) { contents }.units()

        assertEquals(
            listOf(
                ReadingUnit(compiled, "Capítulo 1 — A bruxa", UnitKind.CHAPTER, 1.0, UnitContent.SplitEpub, tocKey = "OEBPS/c1.xhtml"),
                ReadingUnit(compiled, "Capítulo 2", UnitKind.CHAPTER, 2.0, UnitContent.SplitEpub, tocKey = "OEBPS/c2.xhtml"),
                ReadingUnit(compiled, "Epílogo", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = "OEBPS/c2.xhtml#fim"),
            ),
            units,
        )
    }

    @Test
    fun `dado um arquivo que não traz a divisão, então ele fica um capítulo só, o arquivo inteiro`() {
        val whole = file()

        assertEquals(listOf(whole), SplitFiles.expand(library(whole)) { emptyList() }.units())
    }

    @Test
    fun `dado um capítulo num arquivo só e um volume compilado na mesma pasta, então só o volume é dividido, no seu lugar`() {
        val volume = file("Novels/Release That Witch/Vol. 2.epub").copy(kind = UnitKind.VOLUME, number = 2.0)
        val loose = ReadingUnit("Novels/Release That Witch/Cap. 0900.epub", "Cap. 0900", UnitKind.CHAPTER, 900.0, UnitContent.Epub)
        val asked = mutableListOf<String>()

        val units = SplitFiles.expand(library(volume, loose)) { unit ->
            asked += unit.path
            listOf(ContentsChapter("Chapter 31", "c31.xhtml", "Volume 2"), ContentsChapter("Chapter 32", "c32.xhtml", "Volume 2"))
        }.units()

        assertEquals(listOf(volume.path), asked)
        assertEquals(listOf("Chapter 31", "Chapter 32", "Cap. 0900"), units.map { it.name })
        assertEquals(listOf(31.0, 32.0, 900.0), units.map { it.number })
    }

    @Test
    fun `dado um item do sumário sem título, então o capítulo leva o nome do arquivo`() {
        val units = SplitFiles.expand(library(file())) { listOf(ContentsChapter("", "c1.xhtml", null), ContentsChapter("  ", "c2.xhtml", null)) }.units()

        assertEquals(listOf("Release That Witch", "Release That Witch"), units.map { it.name })
        assertEquals(listOf("c1.xhtml", "c2.xhtml"), units.map { it.tocKey })
    }

    @Test
    fun `dado títulos de sumário, então o número do capítulo sai pela mesma regra dos nomes de arquivo`() {
        assertEquals(12.0, FolderConvention.chapterNumber("Capítulo 12 — O cais"))
        assertEquals(3.5, FolderConvention.chapterNumber("Chapter 3.5"))
        assertEquals(7.0, FolderConvention.chapterNumber("7"))
        assertNull(FolderConvention.chapterNumber("Prólogo"))
        assertNull(FolderConvention.chapterNumber("Volume 2"))
    }
}
