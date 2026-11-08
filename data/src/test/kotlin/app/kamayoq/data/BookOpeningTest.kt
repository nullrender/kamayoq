package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.reader.EpubLimits
import app.kamayoq.core.reader.FileTooLargeException
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookOpeningTest {

    private val databaseFile: File = File.createTempFile("kamayoq-test", ".db").also { it.delete() }
    private val cache: File = Files.createTempDirectory("kamayoq-cache").toFile()
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:${databaseFile.path}",
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)
    private val opening = ArchiveOpening(store, cache)

    @AfterTest
    fun cleanUp() {
        driver.close()
        databaseFile.delete()
        cache.deleteRecursively()
    }

    private fun importBooks(vararg paths: String) = store.importLocal(
        "content://test/tree/primary%3AKamayoq",
        FolderConvention.read(paths.toList()),
        paths.associateWith { FileStamp(1_000, 5) },
    )

    private fun chapter(title: String) = store.chapters(store.works().single { it.title == title }.id).single()

    private fun filesInCache() = cache.walkTopDown().filter { it.isFile }.toList()

    /** Counts what the copy took, to tell a copy that stopped from one that read the whole file. */
    private class Counting(private val input: InputStream) : InputStream() {
        var read = 0L

        override fun read(): Int = input.read().also { if (it >= 0) read++ }

        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            input.read(target, offset, length).also { if (it > 0) read += it }
    }

    @Test
    fun `dado Dom Casmurro em EPUB, quando aberto, então as entradas do livro se leem da cópia e o resumo fica guardado`() {
        importBooks("Books/Dom Casmurro.epub")

        val opened = opening.openBook(opening.begin(), chapter("Dom Casmurro"), ByteArrayInputStream(EpubFixtures.short()))

        assertEquals(chapter("Dom Casmurro").id, opened.chapterId)
        assertTrue("META-INF/container.xml" in opened.archive.names)
        assertFalse(chapter("Dom Casmurro").needsContentCheck)
        // Never named as a CBZ: nothing else in the app may take it for one.
        assertEquals(listOf("epub"), filesInCache().map { it.extension })
    }

    @Test
    fun `dado um EPUB maior que o teto, quando aberto, então a cópia para no teto e nada fica no cache`() {
        importBooks("Books/Dom Casmurro.epub")
        val tenMegabytes = Counting(ByteArrayInputStream(ByteArray(10 * 1024 * 1024)))

        val refused = assertFailsWith<FileTooLargeException> {
            opening.openBook(opening.begin(), chapter("Dom Casmurro"), tenMegabytes, EpubLimits(maxFileBytes = 1_000))
        }

        assertEquals(1_000, refused.maxBytes)
        assertTrue(tenMegabytes.read < 1024 * 1024, "Read ${tenMegabytes.read} bytes")
        assertEquals(emptyList(), filesInCache())
    }

    @Test
    fun `dado um livro aberto, quando liberado, então a cópia sai do cache`() {
        importBooks("Books/Dom Casmurro.epub")
        val opened = opening.openBook(opening.begin(), chapter("Dom Casmurro"), ByteArrayInputStream(EpubFixtures.short()))

        opening.release(opened)

        assertEquals(emptyList(), filesInCache())
    }

    @Test
    fun `dado um livro aberto, quando outro é aberto depois, então o primeiro é liberado`() {
        importBooks("Books/Dom Casmurro.epub")
        val book = opening.openBook(opening.begin(), chapter("Dom Casmurro"), ByteArrayInputStream(EpubFixtures.short()))

        opening.openBook(opening.begin(), chapter("Dom Casmurro"), ByteArrayInputStream(EpubFixtures.short()))

        assertFalse(book.copy.exists())
        assertEquals(1, filesInCache().size)
    }
}
