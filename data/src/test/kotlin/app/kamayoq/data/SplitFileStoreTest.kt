package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.Work
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import java.io.File
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Step 4, milestone B: a compiled novel is one file with several chapters, each with its own
// identity and reading (docs/decisions/novel-chapters-2026-11-18.md).
class SplitFileStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-split", ".db").also { it.delete() }
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private val novel = "Novels/Release That Witch/Release That Witch.epub"

    private fun chapter(number: Int, key: String = "OEBPS/c$number.xhtml") =
        ReadingUnit(novel, "Capítulo $number", UnitKind.CHAPTER, number.toDouble(), UnitContent.Epub, tocKey = key)

    private fun import(vararg units: ReadingUnit, stamp: FileStamp = FileStamp(1_000, 1_761_400_000_000)) {
        val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units.toList())
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), units.map { it.path }.distinct().associateWith { stamp })
    }

    private fun chapters() = store.chapters(store.works().single().id)

    private fun reading(fraction: Int) = ChapterProgress(
        readMark = null,
        position = TextPosition(0, 3, "epubcfi(/6/2!/4/$fraction)", BasisPoints.fromStored(fraction), Instant.parse("2026-11-18T12:00:00Z"), "phone-a"),
        completeReads = 0,
    )

    @Test
    fun `dado três capítulos do mesmo arquivo, quando importo, então são três capítulos na ordem, cada um com a sua chave`() {
        import(chapter(1), chapter(2), chapter(3))

        assertEquals(listOf("Capítulo 1", "Capítulo 2", "Capítulo 3"), chapters().map { it.name })
        assertEquals(listOf(novel, novel, novel), chapters().map { it.path })
        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml", "OEBPS/c3.xhtml"), chapters().map { it.tocKey })
        assertEquals(3, chapters().map { it.id }.toSet().size)
    }

    @Test
    fun `dado dois capítulos do mesmo arquivo lidos pela metade, quando importo de novo, então cada um mantém o identificador e o seu lugar`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()
        store.saveProgress(first.id, reading(2_500))
        store.saveProgress(second.id, reading(7_000))

        import(chapter(1), chapter(2))

        assertEquals(listOf(first.id, second.id), chapters().map { it.id })
        assertEquals(2_500, (store.progress(first.id).position as TextPosition).fraction.value)
        assertEquals(7_000, (store.progress(second.id).position as TextPosition).fraction.value)
    }

    @Test
    fun `dado um capítulo do arquivo marcado como lido, então só ele fica lido e a obra reabre nele`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()

        store.saveProgress(second.id, reading(10_000).copy(readMark = ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END)))

        assertEquals(null, store.progress(first.id).readMark)
        assertEquals(ReadMarkSource.READ_TO_END, store.progress(second.id).readMark?.source)
        assertEquals(second.id, store.resumeChapter(store.works().single().id)?.id)
    }

    @Test
    fun `dado um livro inteiro e um arquivo dividido, então o livro tem a chave vazia`() {
        val book = ReadingUnit("Books/Dom Casmurro.epub", "Dom Casmurro", UnitKind.VOLUME, null, UnitContent.Epub)
        val work = Work("Books/Dom Casmurro.epub", "Dom Casmurro", null, WorkType.BOOKS, null, listOf(book))
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(book.path to FileStamp(1, 1)))

        assertEquals(listOf(""), chapters().map { it.tocKey })
    }
}
