package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import java.time.Instant
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

// Slice 2 of the v1 interface: a work's reading state, kept apart from the progress of its chapters.
class ReadingStateStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-state", ".db").also { it.delete() }
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    private fun open(): LibraryStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        drivers += driver
        return LibraryStore(driver)
    }

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        file.delete()
    }

    private fun LibraryStore.import(vararg paths: String) =
        importLocal(source, FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(1_000, 5) })

    private fun LibraryStore.work(title: String) = works().single { it.title == title }.id

    @Test
    fun `dado uma obra recém-chegada, então está sem estado e sem mudança automática`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(StoredReadingState.NONE, store.readingState(store.work("Sakamoto Days")))
    }

    @Test
    fun `dado Quero ler escolhido, quando o banco fecha e abre de novo, então a obra continua em Quero ler`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        store.setReadingState(store.work("Sakamoto Days"), ReadingState.WANT_TO_READ)

        drivers.forEach { it.close() }
        val reopened = open()

        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, automatic = null), reopened.readingState(reopened.work("Sakamoto Days")))
    }

    @Test
    fun `dado uma obra em Lendo, quando escolho deixar sem estado, então fica sem estado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val work = store.work("Sakamoto Days")
        store.setReadingState(work, ReadingState.READING)

        store.setReadingState(work, null)

        assertEquals(StoredReadingState.NONE, store.readingState(work))
    }

    @Test
    fun `dado duas obras, quando escolho o estado de uma, então a outra continua sem estado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Books/Dom Casmurro.epub")

        store.setReadingState(store.work("Sakamoto Days"), ReadingState.DROPPED)

        assertEquals(ReadingState.DROPPED, store.readingState(store.work("Sakamoto Days")).state)
        assertEquals(StoredReadingState.NONE, store.readingState(store.work("Dom Casmurro")))
    }

    @Test
    fun `dado o banco, quando um estado é gravado para uma obra que não existe, então o banco recusa`() {
        open()

        assertFails {
            drivers.last().execute(null, "INSERT INTO reading_state (work_id, state, automatic) VALUES ('work-nobody', 'READING', 0)", 0)
        }
    }

    private val at = Instant.parse("2026-11-20T01:30:00Z")

    private fun page(page: Int, reads: Int = 0) = ChapterProgress(
        readMark = ReadMark(at, ReadMarkSource.READ_TO_END).takeIf { reads > 0 },
        position = StripPosition(page, 40, at, "phone-a"),
        completeReads = reads,
    )

    private fun book(basisPoints: Int) = ChapterProgress(
        readMark = null,
        position = TextPosition(3, 148, "epubcfi(/6/8!/4/2/1:0)", BasisPoints.fromStored(basisPoints), at, "phone-a"),
        completeReads = 0,
    )

    private fun LibraryStore.chapterOf(title: String) = chapters(work(title)).single().id

    @Test
    fun `dado uma obra sem estado, quando o leitor grava o fim de um capítulo, então fica em Lendo, com a mudança automática vinda de sem estado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val chapter = store.chapterOf("Sakamoto Days")
        store.saveProgress(chapter, page(20))

        store.saveProgress(chapter, page(40, reads = 1))

        assertEquals(
            StoredReadingState(ReadingState.READING, AutomaticChange(from = null, at = at)),
            store.readingState(store.work("Sakamoto Days")),
        )
    }

    @Test
    fun `dado uma obra em Quero ler, quando o leitor grava páginas no meio do capítulo, então continua em Quero ler`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val chapter = store.chapterOf("Sakamoto Days")
        store.setReadingState(store.work("Sakamoto Days"), ReadingState.WANT_TO_READ)

        store.saveProgress(chapter, page(5))
        store.saveProgress(chapter, page(39))

        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, null), store.readingState(store.work("Sakamoto Days")))
    }

    @Test
    fun `dado a mudança automática para Lendo, quando desfaço, então volta a Quero ler e a gravação seguinte do mesmo fim não muda de novo`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val work = store.work("Sakamoto Days")
        val chapter = store.chapterOf("Sakamoto Days")
        store.setReadingState(work, ReadingState.WANT_TO_READ)
        store.saveProgress(chapter, page(40, reads = 1))

        store.undoAutomatic(work)
        store.saveProgress(chapter, page(40, reads = 1))

        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, null), store.readingState(work))
    }

    @Test
    fun `dado a mudança automática para Lendo, quando escolho Abandonado, então a obra fica em Abandonado e a mudança automática some`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val work = store.work("Sakamoto Days")
        store.saveProgress(store.chapterOf("Sakamoto Days"), page(40, reads = 1))

        store.setReadingState(work, ReadingState.DROPPED)

        assertEquals(StoredReadingState(ReadingState.DROPPED, null), store.readingState(work))
    }

    @Test
    fun `dado uma obra sem mudança automática, quando peço para desfazer, então nada muda`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val work = store.work("Sakamoto Days")
        store.setReadingState(work, ReadingState.ON_HOLD)

        store.undoAutomatic(work)

        assertEquals(StoredReadingState(ReadingState.ON_HOLD, null), store.readingState(work))
    }

    @Test
    fun `dado um livro posto em pausa aos 30 por cento, quando a leitura chega a 30,5, então fica, e aos 31 volta a Lendo vindo de Em pausa`() {
        val store = open()
        store.import("Books/Dom Casmurro.epub")
        val work = store.work("Dom Casmurro")
        val chapter = store.chapterOf("Dom Casmurro")
        store.saveProgress(chapter, book(3_000))
        store.setReadingState(work, ReadingState.ON_HOLD)

        store.saveProgress(chapter, book(3_050))
        assertEquals(StoredReadingState(ReadingState.ON_HOLD, null), store.readingState(work))

        store.saveProgress(chapter, book(3_100))
        assertEquals(StoredReadingState(ReadingState.READING, AutomaticChange(ReadingState.ON_HOLD, at)), store.readingState(work))
    }

    @Test
    fun `dado um livro sem estado, quando a primeira gravação já está em 50 por cento, como num livro lido antes dos estados, então passa a Lendo`() {
        val store = open()
        store.import("Books/Dom Casmurro.epub")
        val chapter = store.chapterOf("Dom Casmurro")

        store.saveProgress(chapter, book(5_010))

        assertEquals(ReadingState.READING, store.readingState(store.work("Dom Casmurro")).state)
    }

    @Test
    fun `dado um livro que mudou sozinho para Lendo aos 2 por cento, quando desfaço e leio mais 1 por cento, então continua em Quero ler`() {
        val store = open()
        store.import("Books/Dom Casmurro.epub")
        val work = store.work("Dom Casmurro")
        val chapter = store.chapterOf("Dom Casmurro")
        store.setReadingState(work, ReadingState.WANT_TO_READ)
        store.saveProgress(chapter, book(200))

        store.undoAutomatic(work)
        store.saveProgress(chapter, book(300))

        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, null), store.readingState(work))
        assertNull(store.readingState(work).automatic)
    }
}
