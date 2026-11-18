package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.reader.ContentsChapter
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Step 4, piece B6: the reading of the folder opens a compiled novel only to read its table of
// contents, and only when the file is new or changed.
@RunWith(RobolectricTestRunner::class)
class LibraryScanTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val folder = FolderInMemory(app, emptyList()).apply { novels = listOf("Romance") }
    private val twoChapters = listOf(ContentsChapter("Capítulo 1", "c1.xhtml", null), ContentsChapter("Capítulo 2", "c2.xhtml", null))
    private val read = ChapterProgress(ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END), null, 1)

    private fun scan(contents: (String) -> List<ContentsChapter>) = LibraryScan.run(folder, folder.tree, app.store, { true }, contents)

    private fun chapters() = app.store.works().flatMap { app.store.chapters(it.id) }

    private fun readFirstChapter(): String = chapters().first().id.also { app.store.saveProgress(it, read) }

    @Test
    fun `dado um romance compilado já lido da pasta, quando a pasta é lida de novo sem o arquivo mudar, então o arquivo não é aberto outra vez`() {
        scan { twoChapters }
        var opened = 0

        scan {
            opened++
            twoChapters
        }

        assertEquals(0, opened)
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), chapters().map { it.name })
    }

    // Found by the independent review of 18/11/2026.
    @Test
    fun `dado um romance cujo arquivo mudou e não pôde ser lido, quando a pasta é lida, então os capítulos e o que foi lido ficam e a leitura seguinte tenta de novo`() {
        scan { twoChapters }
        val first = readFirstChapter()
        folder.novelsModified = 2

        scan { throw IOException("The file could not be read") }

        assertEquals(listOf("Capítulo 1", "Capítulo 2"), chapters().map { it.name })
        assertEquals(first, chapters().first().id)
        assertEquals(read.readMark, app.store.progress(first).readMark)
        var opened = 0
        scan {
            opened++
            twoChapters
        }
        assertEquals(1, opened)
    }

    @Test
    fun `dado um romance que saiu da pasta e voltou, quando o arquivo não pôde ser lido na volta, então os capítulos voltam com o que foi lido`() {
        scan { twoChapters }
        val first = readFirstChapter()
        folder.novels = emptyList()
        scan { twoChapters }
        assertEquals(emptyList<String>(), chapters().map { it.name })
        folder.novels = listOf("Romance")
        folder.novelsModified = 2

        scan { throw IOException("The file could not be read") }

        assertEquals(listOf("Capítulo 1", "Capítulo 2"), chapters().map { it.name })
        assertEquals(first, chapters().first().id)
        assertEquals(read.readMark, app.store.progress(first).readMark)
    }
}
