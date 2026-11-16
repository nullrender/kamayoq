package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
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
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

// Step 4, piece 20: a book's place goes through the same read, copy and write as a chapter's page,
// so each rule here saves through the store, applies the rule through the store and reads back
// through it. A column the store forgets would come back empty with no error.
class TextProgressTest {

    private val file: File = File.createTempFile("kamayoq-text", ".db").also { it.delete() }
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:${file.path}",
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private fun book(): String {
        val path = "Books/Dom Casmurro.epub"
        store.importLocal("content://test/tree/primary%3AKamayoq", FolderConvention.read(listOf(path)), mapOf(path to FileStamp(1_000, 5)))
        return store.chapters(store.works().single().id).single().id
    }

    private val place = TextPosition(
        section = 2,
        sections = 148,
        cfi = "epubcfi(/6/6!/4/2[cap-2]/10/1:42)",
        fraction = checkNotNull(BasisPoints.fromFraction(0.0137)),
        at = Instant.parse("2026-11-16T22:00:00Z"),
        device = "phone-a",
    )

    private val reading = ChapterProgress(readMark = null, position = place, completeReads = 0)

    @Test
    fun `dado uma posição de livro gravada, quando a leio de volta, então seção, CFI e fração voltam iguais`() {
        val id = book()

        store.saveProgress(id, reading)

        assertEquals(reading, store.progress(id))
    }

    @Test
    fun `dado uma posição de livro gravada, quando marco como lido, então CFI e fração continuam`() {
        val id = book()
        store.saveProgress(id, reading)
        // Marking by hand has no screen yet: it is the read, copy and write any mark will use.
        val mark = ReadMark(Instant.parse("2026-11-16T22:05:00Z"), ReadMarkSource.PERSON)

        store.saveProgress(id, store.progress(id).copy(readMark = mark))

        assertEquals(reading.copy(readMark = mark), store.progress(id))
    }

    @Test
    fun `dado uma posição de livro gravada, quando o mesmo conteúdo é confirmado, então CFI e fração continuam no mesmo capítulo`() {
        val id = book()
        store.confirmContent(id, "hash-1")
        store.saveProgress(id, reading)

        assertEquals(id, store.confirmContent(id, "hash-1"))

        assertEquals(reading, store.progress(id))
    }

    @Test
    fun `dado uma posição de livro gravada, quando o arquivo muda (E1), então a edição antiga guarda CFI e fração e a nova começa do zero`() {
        val id = book()
        store.confirmContent(id, "hash-1")
        store.saveProgress(id, reading)

        val edition = store.confirmContent(id, "hash-2")

        assertNotEquals(id, edition)
        assertEquals(reading, store.progress(id))
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(edition))
    }

    @Test
    fun `dado uma fração ou um CFI impossíveis escritos direto no banco, então o banco recusa`() {
        val id = book()
        val columns = "chapter_id, position_page, position_total, position_at, position_device, complete_reads, " +
            "position_fraction, position_cfi"

        listOf(
            "'$id', 2, 148, 0, 'phone-a', 0, 10001, 'epubcfi(/6/6!)'",
            "'$id', 2, 148, 0, 'phone-a', 0, -1, 'epubcfi(/6/6!)'",
            "'$id', 2, 148, 0, 'phone-a', 0, NULL, 'epubcfi(/6/6!)'",
            "'$id', 2, 148, 0, 'phone-a', 0, 137, NULL",
            "'$id', NULL, NULL, NULL, NULL, 0, 137, 'epubcfi(/6/6!)'",
        ).forEach { values ->
            assertFails("accepted: $values") { driver.execute(null, "INSERT INTO chapter_progress ($columns) VALUES ($values)", 0) }
        }
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(id))
    }
}
