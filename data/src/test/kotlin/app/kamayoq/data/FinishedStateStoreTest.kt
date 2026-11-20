package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// The "finish" rule (progress contract, section 5) as the reader's saves meet it.
class FinishedStateStoreTest {
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)
    private val completed = Publication(PublicationStatus.COMPLETED, 0)

    @AfterTest
    fun close() = driver.close()

    private fun chapter(id: Int, format: ChapterFormat = ChapterFormat.ARCHIVE) =
        ImportedChapter(id.toString(), "Cap. $id", UnitKind.CHAPTER, id.toDouble(), format, "20|100", 20, "Cap. $id.cbz", 7)

    private fun import(publication: Publication, type: WorkType = WorkType.COMICS, chapters: List<ImportedChapter> = listOf(chapter(1), chapter(2))) {
        val details = WorkDetails(emptyList(), "", emptyList(), publication)
        store.importKavita("server-1", listOf(ImportedWork("12", "Gantz", type, null, 2, chapters, details)))
    }

    private fun work() = store.works().single().id

    private fun chapterIds() = store.chapters(work()).map { it.id }

    /** The chapter read to its end in the reader, at [minute] past noon. */
    private fun readToEnd(chapterId: String, minute: Int) {
        val at = Instant.parse("2026-11-20T12:%02d:00Z".format(minute))
        store.saveProgress(chapterId, ChapterProgress(ReadMark(at, ReadMarkSource.READ_TO_END), StripPosition(20, 20, at, "phone-a"), 1))
    }

    @Test
    fun `dado um quadrinho concluído sem nada faltando, quando o último capítulo termina com o primeiro já lido, então vai para Lido sozinho, com Lendo como o anterior`() {
        import(completed)
        val (first, last) = chapterIds()
        readToEnd(first, 1)

        readToEnd(last, 2)

        assertEquals(
            StoredReadingState(ReadingState.COMPLETED, AutomaticChange(ReadingState.READING, Instant.parse("2026-11-20T12:02:00Z"))),
            store.readingState(work()),
        )
    }

    @Test
    fun `dado a obra posta em Lido sozinha, quando desfaço, então volta a Lendo`() {
        import(completed)
        val (first, last) = chapterIds()
        readToEnd(first, 1)
        readToEnd(last, 2)

        store.undoAutomatic(work())

        assertEquals(StoredReadingState(ReadingState.READING, null), store.readingState(work()))
    }

    @Test
    fun `dado a publicação em andamento, quando o último capítulo termina com tudo lido, então fica em Lendo`() {
        import(Publication(PublicationStatus.ONGOING, 0))
        val (first, last) = chapterIds()
        readToEnd(first, 1)

        readToEnd(last, 2)

        assertEquals(ReadingState.READING, store.readingState(work()).state)
    }

    @Test
    fun `dado a publicação concluída, quando o último capítulo termina antes do primeiro, então fica em Lendo`() {
        import(completed)
        val (_, last) = chapterIds()

        readToEnd(last, 2)

        assertEquals(ReadingState.READING, store.readingState(work()).state)
    }

    @Test
    fun `dado uma obra de um capítulo só, sem estado e concluída, quando ele termina, então vai direto para Lido, com nenhum estado como o anterior`() {
        import(completed, chapters = listOf(chapter(1)))

        readToEnd(chapterIds().single(), 1)

        assertEquals(
            StoredReadingState(ReadingState.COMPLETED, AutomaticChange(null, Instant.parse("2026-11-20T12:01:00Z"))),
            store.readingState(work()),
        )
    }

    @Test
    fun `dado um livro do Kavita com publicação concluída, quando ele termina, então não vai para Lido sozinho`() {
        import(completed, type = WorkType.BOOKS, chapters = listOf(chapter(1, ChapterFormat.EPUB)))

        readToEnd(chapterIds().single(), 1)

        assertNotEquals(ReadingState.COMPLETED, store.readingState(work()).state)
    }

    @Test
    fun `dado a obra já em Lido, quando releio o último capítulo até o fim, então nada muda e não há mudança automática`() {
        import(completed)
        val (first, last) = chapterIds()
        readToEnd(first, 1)
        store.setReadingState(work(), ReadingState.COMPLETED)

        readToEnd(last, 2)

        assertEquals(StoredReadingState(ReadingState.COMPLETED, null), store.readingState(work()))
    }
}
