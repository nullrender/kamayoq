package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Step 4, milestone C2: what the store keeps of a Kavita book's place for the sync, with no server.
// The exchange with the real Kavita is in KavitaProgressSyncTest.
class KavitaTextStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private fun chapter(path: String, format: ChapterFormat, pages: Int, number: Double) =
        ImportedChapter(path, "Cap. $path", UnitKind.CHAPTER, number, format, "$pages|100", pages, "Cap. $path", 7)

    /** One Kavita work with a CBZ chapter (91) and a book (92), as a mixed series could come. */
    private fun work(): Pair<String, String> {
        store.importKavita(
            "server-1",
            listOf(ImportedWork("12", "Obra mista", WorkType.COMICS, null, 2, listOf(chapter("91", ChapterFormat.ARCHIVE, 12, 1.0), chapter("92", ChapterFormat.EPUB, 3, 2.0)))),
        )
        val chapters = store.chapters(store.works().single().id)
        return chapters.single { it.path == "91" }.id to chapters.single { it.path == "92" }.id
    }

    private val place = TextPosition(1, 3, "epubcfi(/6/4!/4/2/1:0)", checkNotNull(BasisPoints.fromFraction(0.4)), Instant.EPOCH, "phone-a")

    @Test
    fun `dado um livro do Kavita, quando gravo um lugar com marcador, então ele espera na fila com o CFI`() {
        val (_, book) = work()

        store.saveProgress(book, ChapterProgress(null, place, 0), marker = "//body/p[2]")

        val item = checkNotNull(store.syncItem(book))
        assertEquals(true, item.isText)
        assertEquals(Triple("TO_SEND", "//body/p[2]", place.cfi), Triple(item.outboxState, item.outboxMarker, item.outboxCfi))
        assertEquals(1, item.outboxPage)
    }

    // From the independent review of milestone C (18/11): only the end block's save makes the queued
    // place the book read (decision C-3), not the read mark, which stays through a reread.
    @Test
    fun `dado o bloco de fim num livro do Kavita, quando gravo, então a fila guarda o livro lido`() {
        val (_, book) = work()
        val last = place.copy(section = 2)

        store.saveProgress(book, ChapterProgress(ReadMark(Instant.EPOCH, ReadMarkSource.READ_TO_END), last, 1), marker = "//body/p[2]")

        assertEquals(3, checkNotNull(store.syncItem(book)).outboxPage)
    }

    @Test
    fun `dado uma releitura na última seção de um livro já lido, quando gravo, então a fila guarda a seção`() {
        val (_, book) = work()
        val last = place.copy(section = 2)
        val read = ChapterProgress(ReadMark(Instant.EPOCH, ReadMarkSource.READ_TO_END), last, 1)
        store.saveProgress(book, read, marker = "//body/p[2]")

        store.saveProgress(book, read.copy(position = last.copy(cfi = "epubcfi(/6/8!/4/4/1:0)")), marker = "//body/p[3]")

        assertEquals(2, checkNotNull(store.syncItem(book)).outboxPage)
    }

    @Test
    fun `dado um capítulo de imagens do Kavita, quando gravo uma página, então nada vai à fila sozinho`() {
        val (comic, _) = work()

        store.saveProgress(comic, ChapterProgress(null, StripPosition(3, 12, Instant.EPOCH, "phone-a"), 0))

        assertNull(checkNotNull(store.syncItem(comic)).outboxState)
    }

    // Found reading the code before milestone C (docs/research/kavita-epub-map-2026-11-18.md): the
    // work's resume pointed at a book, and settling an adopted image chapter read it as an image.
    @Test
    fun `dado uma obra que reabre num livro lido aqui, quando o Kavita traz um capítulo de imagens, então a obra se acerta sem erro`() {
        val (comic, book) = work()
        store.saveProgress(book, ChapterProgress(null, place, 0))
        val before = store.resumeOf(book)

        store.adoptRemote(comic, 5, 12, "x", Instant.EPOCH, LibraryStore.PREVIOUS, moveResume = false)
        store.settleResume(comic, localMoved = false, before)

        assertEquals(comic, store.resumeOf(comic)?.chapterId)
    }

    @Test
    fun `dado um lugar adotado do Kavita, então o marcador e o CFI ficam no acordo e o lugar é do Kavita`() {
        val (_, book) = work()

        store.adoptRemoteText(book, app.kamayoq.core.kavita.KavitaPlace(2, "//body/p[2]"), 3, "x", Instant.EPOCH)

        val item = checkNotNull(store.syncItem(book))
        assertEquals("//body/p[2]", store.agreedMarker(book))
        assertEquals(item.agreedCfi, (store.progress(book).position as TextPosition).cfi)
        assertEquals("kavita", store.progress(book).position?.device)
    }
}
