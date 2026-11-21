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

// Marking chapters read and unread by hand (progress contract, 2b and section 5), for the Work screen.
class ManualMarksStoreTest {
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)
    private val noon = Instant.parse("2026-11-20T12:00:00Z")
    private val later = Instant.parse("2026-11-20T13:00:00Z")

    @AfterTest
    fun close() = driver.close()

    private fun import(publication: Publication = Publication.UNKNOWN, chapters: Int = 3) {
        val list = (1..chapters).map {
            ImportedChapter(it.toString(), "Cap. $it", UnitKind.CHAPTER, it.toDouble(), ChapterFormat.ARCHIVE, "20|100", 20, "Cap. $it.cbz", 7)
        }
        val details = WorkDetails(emptyList(), "", emptyList(), publication)
        store.importKavita("server-1", listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, list, details)))
    }

    private fun work() = store.works().single().id

    private fun ids() = store.chapters(work()).map { it.id }

    private val halfway = ChapterProgress(null, StripPosition(7, 20, noon, "phone-a"), 0)

    @Test
    fun `dado dois capítulos não lidos, quando marco os dois como lidos, então cada um ganha a marca da pessoa com a hora, e posição e leituras completas não mudam`() {
        import()
        val (first, second) = ids()
        store.saveProgress(first, halfway)

        store.markRead(work(), listOf(first, second), later)

        assertEquals(halfway.copy(readMark = ReadMark(later, ReadMarkSource.PERSON)), store.progress(first))
        assertEquals(ChapterProgress(ReadMark(later, ReadMarkSource.PERSON), null, 0), store.progress(second))
    }

    @Test
    fun `dado um capítulo já lido até o fim, quando o marco de novo como lido, então ele guarda a marca que tinha`() {
        import()
        val first = ids().first()
        val read = ChapterProgress(ReadMark(noon, ReadMarkSource.READ_TO_END), StripPosition(20, 20, noon, "phone-a"), 1)
        store.saveProgress(first, read)

        store.markRead(work(), listOf(first), later)

        assertEquals(read, store.progress(first))
    }

    @Test
    fun `dado três capítulos e um já lido, quando marco os três como lidos, então a mudança conta só os dois que mudaram`() {
        import()
        store.markRead(work(), listOf(ids().first()), noon)

        val change = store.markRead(work(), ids(), later)

        assertEquals(2, change.changed)
    }

    @Test
    fun `dado capítulos não lidos, quando os marco como não lidos, então a mudança conta zero`() {
        import()

        val change = store.markUnread(work(), ids(), later)

        assertEquals(0, change.changed)
    }

    @Test
    fun `dado um capítulo lido com posição, quando o marco como não lido, então perde só a marca`() {
        import()
        val first = ids().first()
        store.saveProgress(first, ChapterProgress(ReadMark(noon, ReadMarkSource.READ_TO_END), StripPosition(20, 20, noon, "phone-a"), 1))

        store.markUnread(work(), listOf(first), later)

        assertEquals(ChapterProgress(null, StripPosition(20, 20, noon, "phone-a"), 1), store.progress(first))
    }

    @Test
    fun `dado uma marcação de três capítulos, um já lido antes, quando desfaço, então cada um volta à marca que tinha`() {
        import()
        val (first, second, third) = ids()
        store.markRead(work(), listOf(first), noon)

        val change = store.markRead(work(), listOf(first, second, third), later)
        store.undoMarks(change)

        assertEquals(listOf(ReadMark(noon, ReadMarkSource.PERSON), null, null), ids().map { store.progress(it).readMark })
    }

    @Test
    fun `dado uma desmarcação, quando desfaço, então as marcas voltam`() {
        import()
        val (first, second) = ids()
        store.markRead(work(), listOf(first, second), noon)

        store.undoMarks(store.markUnread(work(), listOf(first, second), later))

        assertEquals(listOf(ReadMark(noon, ReadMarkSource.PERSON), ReadMark(noon, ReadMarkSource.PERSON)), listOf(first, second).map { store.progress(it).readMark })
    }

    @Test
    fun `dado uma obra sem estado, quando marco o primeiro capítulo como lido, então muda sozinha para Lendo`() {
        import()

        store.markRead(work(), listOf(ids().first()), later)

        assertEquals(StoredReadingState(ReadingState.READING, AutomaticChange(null, later)), store.readingState(work()))
    }

    @Test
    fun `dado uma obra em Em pausa, quando marco um capítulo como lido, então ela continua Em pausa, porque marcar não é retomar`() {
        import()
        store.setReadingState(work(), ReadingState.ON_HOLD)

        store.markRead(work(), listOf(ids().first()), later)

        assertEquals(StoredReadingState(ReadingState.ON_HOLD, null), store.readingState(work()))
    }

    @Test
    fun `dado uma obra concluída na origem e em Lendo, quando marco todos como lidos, então vai para Lido sozinha, e desfazer volta marcas e estado`() {
        import(Publication(PublicationStatus.COMPLETED, 0))
        store.setReadingState(work(), ReadingState.READING)

        val change = store.markRead(work(), ids(), later)
        val finished = store.readingState(work())
        store.undoMarks(change)

        assertEquals(StoredReadingState(ReadingState.COMPLETED, AutomaticChange(ReadingState.READING, later)), finished)
        assertEquals(StoredReadingState(ReadingState.READING, null), store.readingState(work()))
        assertEquals(listOf(null, null, null), ids().map { store.progress(it).readMark })
    }

    @Test
    fun `dado uma obra concluída na origem, quando marco todos menos o último, então fica em Lendo`() {
        import(Publication(PublicationStatus.COMPLETED, 0))

        store.markRead(work(), ids().dropLast(1), later)

        assertEquals(ReadingState.READING, store.readingState(work()).state)
    }

    @Test
    fun `dado uma obra em Lido, quando marco um capítulo como não lido, então o estado não muda`() {
        import()
        store.markRead(work(), ids(), noon)
        store.setReadingState(work(), ReadingState.COMPLETED)

        store.markUnread(work(), listOf(ids().last()), later)

        assertEquals(StoredReadingState(ReadingState.COMPLETED, null), store.readingState(work()))
    }

    @Test
    fun `dado uma marcação feita na hora do relógio, com mais casas que o milissegundo, quando desfaço, então o estado que ela mudou volta junto`() {
        import()
        // Android's clock gives micro- and nanoseconds; the database keeps milliseconds.
        val now = Instant.parse("2026-11-20T13:00:00.123456789Z")

        store.undoMarks(store.markRead(work(), ids().take(2), now))

        assertEquals(StoredReadingState.NONE, store.readingState(work()))
        assertEquals(listOf(null, null, null), ids().map { store.progress(it).readMark })
    }
}
