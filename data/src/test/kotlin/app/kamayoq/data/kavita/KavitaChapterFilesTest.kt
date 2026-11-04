package app.kamayoq.data.kavita

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.data.StoredChapter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KavitaChapterFilesTest {
    private val folder: File = Files.createTempDirectory("kamayoq-files").toFile()

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    private fun chapter(needsCheck: Boolean) = StoredChapter(
        "c", "w", "9", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, present = true,
        needsContentCheck = needsCheck, origin = "kavita",
    )

    private class Answers(private val write: (File) -> Unit) : KavitaClient() {
        override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) =
            write(target)
    }

    @Test
    fun `dado um arquivo a conferir e nenhum servidor, quando peço o arquivo, então vem o guardado, marcado como desatualizado`() {
        File(folder, "9.cbz").writeText("old")
        val files = KavitaChapterFiles(Answers { throw IOException("No network") }, folder)

        val kept = files.file("http://a", "key", chapter(needsCheck = true))

        assertEquals("old", kept.file.readText())
        assertTrue(kept.outdated)
    }

    @Test
    fun `dado um capítulo guardado e a chave ilegível, quando peço o arquivo, então vem o guardado`() {
        File(folder, "9.cbz").writeText("kept")
        val files = KavitaChapterFiles(Answers { error("Must not download") }, folder)

        val kept = files.file(chapter(needsCheck = false), stillWanted = { true }) { throw IOException("No Kavita key") }

        assertEquals("kept", kept.file.readText())
    }

    @Test
    fun `dado um capítulo a conferir e a chave ilegível, quando peço o arquivo, então vem o guardado, desatualizado`() {
        File(folder, "9.cbz").writeText("kept")
        val files = KavitaChapterFiles(Answers { error("Must not download") }, folder)

        val kept = files.file(chapter(needsCheck = true), stillWanted = { true }) { throw IOException("No Kavita key") }

        assertTrue(kept.outdated)
    }

    @Test
    fun `dado rascunhos de um processo que morreu, quando o app abre, então eles somem e o capítulo guardado fica`() {
        File(folder, "chapter-9-123.part").writeText("half")
        File(folder, "9.cbz").writeText("kept")

        KavitaChapterFiles(Answers { }, folder)

        assertEquals(listOf("9.cbz"), folder.list().orEmpty().toList())
    }

    @Test
    fun `dado um arquivo novo, quando baixo, então ele não está desatualizado`() {
        val files = KavitaChapterFiles(Answers { it.writeText("new") }, folder)

        val kept = files.file("http://a", "key", chapter(needsCheck = true))

        assertEquals("new", kept.file.readText())
        assertFalse(kept.outdated)
    }

    @Test
    fun `dado uma abertura antiga mais lenta, quando a nova começa depois, então fica o arquivo da nova`() {
        val oldStarted = CountDownLatch(1)
        val newAsked = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val files = KavitaChapterFiles(
            Answers {
                if (calls.incrementAndGet() == 1) {
                    oldStarted.countDown()
                    newAsked.await()
                    Thread.sleep(100)
                    it.writeText("old answer")
                } else {
                    it.writeText("new answer")
                }
            },
            folder,
        )
        fun opening() = thread {
            try {
                files.file("http://a", "key", chapter(needsCheck = true))
            } catch (e: Throwable) {
                failures += e
            }
        }

        val older = opening()
        oldStarted.await()
        val newer = opening()
        // The newer one must have got as far as it can: waiting its turn, or already downloading.
        while (newer.state == Thread.State.RUNNABLE || newer.state == Thread.State.NEW) {
            if (calls.get() == 2) break
            Thread.sleep(5)
        }
        newAsked.countDown()
        older.join()
        newer.join()

        assertEquals(emptyList(), failures.toList())
        assertEquals(2, calls.get())
        assertEquals("new answer", File(folder, "9.cbz").readText())
        assertEquals(listOf("9.cbz"), folder.list().orEmpty().toList())
    }

    @Test
    fun `dado um download que falha sem nada guardado, quando peço o arquivo, então a falha aparece e nada sobra`() {
        val files = KavitaChapterFiles(Answers { throw IOException("No network") }, folder)

        kotlin.test.assertFailsWith<IOException> { files.file("http://a", "key", chapter(needsCheck = true)) }

        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }
}
