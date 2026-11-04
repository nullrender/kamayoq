package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.StripPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ArchiveOpeningTest {

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

    /** A small CBZ: each entry's content is its own name, so pages are easy to tell apart. */
    private fun cbz(vararg names: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (name in names) {
                // A fixed time, or two builds of one archive differ when the clock turns between them.
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun importChapters(vararg paths: String) = store.importLocal(
        "content://test/tree/primary%3AKamayoq",
        FolderConvention.read(paths.toList()),
        paths.associateWith { FileStamp(1_000, 5) },
    )

    private fun chapter(name: String) = store.chapters(store.works().single().id).single { it.name == name }

    private fun pagesOf(opened: OpenedArchive) =
        opened.pages.map { key -> opened.open(key).use { it.readBytes().decodeToString() } }

    private fun filesInCache() = cache.walkTopDown().filter { it.isFile }.map { it.name }.toList()

    @Test
    fun `dado um capítulo novo em CBZ, quando aberto, então as páginas saem na ordem e o resumo fica guardado`() {
        importChapters("Comics/X/Cap. 1.cbz")

        val opened = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("2.jpg", "1.jpg")))

        assertEquals(chapter("Cap. 1").id, opened.chapterId)
        assertEquals(listOf("1.jpg", "2.jpg"), pagesOf(opened))
        assertFalse(chapter("Cap. 1").needsContentCheck)
    }

    private val halfway = ChapterProgress(
        readMark = null,
        position = StripPosition(1, 2, Instant.parse("2026-10-28T14:20:00Z"), "phone-a"),
        completeReads = 0,
    )

    @Test
    fun `dado um capítulo com progresso, quando o mesmo CBZ é aberto de novo, então é o mesmo capítulo com o progresso`() {
        importChapters("Comics/X/Cap. 1.cbz")
        val first = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg", "2.jpg")))
        store.saveProgress(first.chapterId, halfway)

        val again = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg", "2.jpg")))

        assertEquals(first.chapterId, again.chapterId)
        assertEquals(halfway, store.progress(again.chapterId))
    }

    @Test
    fun `dado um capítulo com progresso, quando o CBZ trocado por outro conteúdo é aberto antes de a pasta ser relida, então nasce um capítulo do zero e o antigo guarda o progresso`() {
        // The open used to trust the flag of the last scan.
        importChapters("Comics/X/Cap. 1.cbz")
        val old = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg", "2.jpg")))
        store.saveProgress(old.chapterId, halfway)

        val replaced = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("other-1.jpg", "other-2.jpg")))

        assertNotEquals(old.chapterId, replaced.chapterId)
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(replaced.chapterId))
        assertEquals(halfway, store.progress(old.chapterId))
    }

    @Test
    fun `dado dois capítulos abertos um depois do outro, então só a cópia do último fica no cache`() {
        importChapters("Comics/X/Cap. 1.cbz", "Comics/X/Cap. 2.cbz")

        opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("a.jpg")))
        val second = opening.open(chapter("Cap. 2"), ByteArrayInputStream(cbz("b.jpg")))

        assertEquals(listOf("b.jpg"), pagesOf(second))
        assertEquals(1, filesInCache().size)
    }

    // The file of a chapter could close while its pages were read.

    @Test
    fun `dado uma página ainda sendo lida, quando o capítulo é liberado, então a leitura termina e só depois o arquivo fecha e sai do cache`() {
        importChapters("Comics/X/Cap. 1.cbz")
        val opened = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg", "2.jpg")))
        val reading = opened.open(opened.pages.first())

        opening.release(opened)

        assertEquals("1.jpg", reading.readBytes().decodeToString())
        assertEquals(1, filesInCache().size)
        reading.close()
        assertEquals(emptyList(), filesInCache())
    }

    @Test
    fun `dado um capítulo liberado, quando se pede uma página, então dá erro de leitura`() {
        importChapters("Comics/X/Cap. 1.cbz")
        val opened = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg")))

        opening.release(opened)

        assertFailsWith<IOException> { opened.open(opened.pages.first()) }
    }

    @Test
    fun `dado um capítulo aberto, quando a limpeza de sobras roda, então ele continua legível e só as sobras saem`() {
        File(cache, "left-by-a-closed-app.cbz").writeText("old copy")
        importChapters("Comics/X/Cap. 1.cbz")
        val opened = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("1.jpg")))

        opening.clearLeftovers()

        assertEquals(listOf("1.jpg"), pagesOf(opened))
        assertEquals(1, filesInCache().size)
        assertFalse("left-by-a-closed-app.cbz" in filesInCache())
    }

    /** A CBZ that waits at its first read until [go] opens; [started] tells when it got there. */
    private class SlowInput(bytes: ByteArray) : InputStream() {
        val started = CountDownLatch(1)
        val go = CountDownLatch(1)
        private val inner = ByteArrayInputStream(bytes)

        override fun read(): Int = waitThen { inner.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = waitThen { inner.read(b, off, len) }

        private fun waitThen(read: () -> Int): Int {
            started.countDown()
            check(go.await(10, TimeUnit.SECONDS)) { "The test never let the slow CBZ go" }
            return read()
        }
    }

    @Test
    fun `dado um capítulo A que demora a abrir e um B aberto depois, quando A termina por último, então B continua legível e A fecha sozinho`() {
        importChapters("Comics/X/Cap. 1.cbz", "Comics/X/Cap. 2.cbz")
        val slow = SlowInput(cbz("a.jpg"))
        var failureOfA: Throwable? = null
        val openingA = thread {
            try {
                opening.open(chapter("Cap. 1"), slow)
            } catch (e: Throwable) {
                failureOfA = e
            }
        }
        check(slow.started.await(10, TimeUnit.SECONDS)) { "A never started" }

        // The person left A and opened B, which is ready first.
        val b = opening.open(chapter("Cap. 2"), ByteArrayInputStream(cbz("b.jpg")))
        slow.go.countDown()
        openingA.join()

        assertEquals(listOf("b.jpg"), pagesOf(b))
        assertIs<IOException>(failureOfA)
        assertEquals(1, filesInCache().size)
    }

    @Test
    fun `dado uma abertura que pegou o número e atrasou antes da cópia, quando outra abre e termina, então a atrasada não fecha a outra`() {
        importChapters("Comics/X/Cap. 1.cbz", "Comics/X/Cap. 2.cbz")
        // A took its number, then waited on the folder before handing its file.
        val requestOfA = opening.begin()

        val b = opening.open(chapter("Cap. 2"), ByteArrayInputStream(cbz("b.jpg")))

        assertFailsWith<IOException> { opening.open(requestOfA, chapter("Cap. 1"), ByteArrayInputStream(cbz("a.jpg"))) }
        assertEquals(listOf("b.jpg"), pagesOf(b))
        assertEquals(1, filesInCache().size)
    }

    @Test
    fun `dado um capítulo trocado aberto duas vezes, quando a abertura antiga termina por último, então a edição nova continua a atual`() {
        importChapters("Comics/X/Cap. 1.cbz")
        val first = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("old.jpg")))
        store.saveProgress(first.chapterId, ChapterProgress(null, StripPosition(1, 2, Instant.EPOCH, "phone-a"), 0))
        opening.release(first)
        val slow = SlowInput(cbz("old.jpg"))
        var failureOfOld: Throwable? = null
        val old = thread {
            try {
                opening.open(chapter("Cap. 1"), slow)
            } catch (e: Throwable) {
                failureOfOld = e
            }
        }
        check(slow.started.await(10, TimeUnit.SECONDS)) { "The old opening never started" }

        // The old opening read the old file; the new one came after.
        val new = opening.open(chapter("Cap. 1"), ByteArrayInputStream(cbz("new.jpg")))
        slow.go.countDown()
        old.join()

        assertIs<IOException>(failureOfOld)
        assertEquals(new.chapterId, store.currentEdition(first.chapterId))
        assertEquals(listOf("new.jpg"), pagesOf(new))
    }
}
