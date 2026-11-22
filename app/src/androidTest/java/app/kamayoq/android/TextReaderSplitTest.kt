package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderChapters
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.SplitReading
import app.kamayoq.data.SplitContents
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, piece B7: a compiled novel is one file, and reading crosses its chapters with no reload
// (docs/decisions/novel-chapters-2026-11-18.md, section 3).
@RunWith(AndroidJUnit4::class)
class TextReaderSplitTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private lateinit var chapters: ReaderChapters
    private lateinit var reading: SplitReading
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val saves = CopyOnWriteArrayList<SplitReading.Saved>()
    private val workChapterOpened = CopyOnWriteArrayList<String>()

    private fun writeBook(book: ByteArray) {
        file = File(compose.activity.cacheDir, "split.epub").apply { writeBytes(book) }
        archive = EpubArchive.open(file)
        val contents = SplitContents.of(archive)
        chapters = ReaderChapters(contents.chapters.map { it.key }, contents.chapterOfItem)
    }

    @After
    fun closeBook() {
        // Release the screen before the archive: a WebView may still be reading an entry.
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive.close()
        file.delete()
    }

    /**
     * Opens [book] at the chapter of place [opened] in the file and waits for the restore. No chapter
     * was ever read, unless [savedAtOpened] gives the opened one a reading.
     */
    private fun open(
        opened: Int,
        book: ByteArray = EpubFixtures.novel(),
        savedAtOpened: ChapterProgress = ChapterProgress.NEVER_OPENED,
        withChapterList: Boolean = false,
    ) {
        writeBook(book)
        reading = SplitReading(List(chapters.keys.size) { if (it == opened) savedAtOpened else ChapterProgress.NEVER_OPENED }, opened, "emulator")
        val session = SplitText(reading, chapters) { saves += it }
        // The work's chapters are the file's, as in a novel that is one split file.
        val rows = chapters.keys.indices.map { ReaderChapterRow("chapter-$it", "Capítulo da lista $it", read = false, percent = null) }
        compose.setContent {
            TextReader(
                archive, "Novela em volumes", session, onBack = {}, onMessage = messages::add, type = WorkType.NOVELS, end = TextEnd.Chapter(null) {},
                chapterRows = rows.takeIf { withChapterList },
                chapterIdOf = { inFile -> "chapter-${inFile ?: reading.current}" },
                fileChapterOf = { id -> id.removePrefix("chapter-").toIntOrNull() },
                onOpenWorkChapter = { workChapterOpened += it },
            )
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> return
                is ReaderMessage.Failed -> throw AssertionError("Reader said: $message")
                else -> Unit
            }
        }
        throw AssertionError("Reader did not restore")
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete)
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun visibleText(): String =
        JSONTokener(evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''")).nextValue() as String

    /** Waits until the screen shows [text], or fails saying what is there. */
    private fun assertShows(text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var shown = visibleText()
        while (!shown.contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(500)
            shown = visibleText()
        }
        assertTrue("The screen shows: ${shown.take(200)}", shown.contains(text))
    }

    private fun button(label: Int) = compose.onNodeWithText(compose.activity.getString(label))

    /** Next page until a place is saved in the chapter of place [chapter], as a person reading on. */
    private fun readInto(chapter: Int) {
        repeat(80) {
            if (saves.any { it.chapter == chapter }) return
            compose.pageAction(R.string.reader_next_page)
            compose.waitForIdle()
            Thread.sleep(400)
        }
        throw AssertionError("No place saved in chapter $chapter after 80 screens; saved: ${saves.map { it.chapter to (it.progress.position as? TextPosition)?.fraction?.value }}")
    }

    private fun ChapterProgress.points() = (position as? TextPosition)?.fraction?.value

    @Test
    fun `dado o capítulo 4 nunca aberto quando o arquivo abre nele então a tela mostra o começo do capítulo 4`() {
        open(opened = 3)

        assertShows("Capítulo 4")
        // The first words of chapter 4, and none of the cover.
        assertShows("m4n1 ")
    }

    // Found by the independent review of 18/11/2026: with a place that no longer resolves, the file
    // opened at its start, and the first move saved that place into the wrong chapter.
    @Test
    fun `dado um capítulo cujo lugar guardado não existe mais no arquivo quando ele abre então a tela mostra o começo desse capítulo`() {
        val gone = TextPosition(section = 7, sections = 8, cfi = "epubcfi(/6/40!/4/2)", fraction = BasisPoints.fromStored(4_000), at = Instant.parse("2026-11-18T12:00:00Z"), device = "emulator")

        open(opened = 3, savedAtOpened = ChapterProgress(readMark = null, position = gone, completeReads = 0))

        assertShows("Capítulo 4")
    }

    // Found by the review of the fixes: a place whose section is still in the file but whose path
    // inside it is gone opened at the section's start, which is chapter 4, not chapter 5.
    @Test
    fun `dado um capítulo cujo lugar guardado aponta para um trecho que não existe mais na seção quando ele abre então a tela mostra o começo desse capítulo`() {
        val stale = TextPosition(section = 4, sections = 5, cfi = "epubcfi(/6/10!/4/999)", fraction = BasisPoints.fromStored(4_000), at = Instant.parse("2026-11-18T12:00:00Z"), device = "emulator")

        open(opened = 4, savedAtOpened = ChapterProgress(readMark = null, position = stale, completeReads = 0))

        assertShows("Capítulo 5")
    }

    // Found by the same review: a section above 512 KiB is shown in pieces, and in a piece the fork
    // names the last chapter of the whole section for any page past the piece's last heading.
    @Test
    fun `dado um romance compilado numa seção em pedaços quando leio o último capítulo do primeiro pedaço então só ele ganha o lugar e ao passar adiante só ele fica lido`() {
        open(opened = 0, book = EpubFixtures.positionsCompiled())
        // The last chapter whose heading is in the first piece: the one the fork's answer goes wrong for.
        val last = evaluate(
            "Math.max(...[...document.querySelector('foliate-view').renderer.getContents()[0].doc.querySelectorAll('h2[id]')].map(heading => Number(heading.id.slice(4))))",
        ).toInt()
        assertTrue("Chapters in the first piece: $last of ${chapters.keys.size}", last in 2 until chapters.keys.size)
        evaluate("document.querySelector('foliate-view').goTo('OEBPS/s1.xhtml#cap-$last'); null")
        compose.waitUntil(15_000) { saves.any { it.chapter == last - 1 } }
        saves.clear()

        repeat(3) {
            compose.pageAction(R.string.reader_next_page)
            compose.waitForIdle()
            Thread.sleep(400)
        }

        assertEquals("Chapters given a place inside chapter $last", setOf(last - 1), saves.map { it.chapter }.toSet())
        assertEquals(emptyList<Int>(), saves.filter { it.progress.readMark != null }.map { it.chapter })

        readInto(chapter = last)

        assertEquals(listOf(last - 1), saves.filter { it.progress.readMark != null }.map { it.chapter }.distinct())
        assertEquals(last, reading.current)
    }

    // Found by the review of the fixes: a page of a piece before its first heading took its chapter
    // from fractions, the chapters' starts measured in text and the page in markup, which are far
    // apart where the markup is dense, so the page named a chapter well ahead of it.
    @Test
    fun `dado um romance compilado de marcação desigual quando passo de um pedaço ao seguinte no meio de um capítulo então o lugar fica nesse capítulo`() {
        open(opened = 0, book = EpubFixtures.unevenCompiled())
        val last = evaluate(
            "Math.max(...[...document.querySelector('foliate-view').renderer.getContents()[0].doc.querySelectorAll('h2[id]')].map(heading => Number(heading.id.slice(4))))",
        ).toInt()
        assertTrue("Chapters in the first piece: $last of ${chapters.keys.size}", last in 2 until chapters.keys.size)
        evaluate("document.querySelector('foliate-view').goTo('OEBPS/s1.xhtml#cap-$last'); null")
        compose.waitUntil(15_000) { saves.any { it.chapter == last - 1 } }
        saves.clear()

        readInto(chapter = last)

        assertEquals("Chapters given a place from chapter $last into the next", setOf(last - 1, last), saves.map { it.chapter }.toSet())
        assertShows("Capítulo ${last + 1}")
    }

    @Test
    fun `dado a leitura do capítulo 1 quando avanço até entrar no capítulo 2 então o 1 fica lido no fim e o lugar passa para o 2`() {
        open(opened = 0)

        readInto(chapter = 1)

        val first = saves.filter { it.chapter == 0 }
        val inside = first.dropLast(1).mapNotNull { it.progress.points() }
        // Page after page inside chapter 1, the chapter's own fraction grows from above zero.
        assertTrue("Fractions inside chapter 1: $inside", inside.size >= 2 && inside == inside.sorted() && inside.first() > 0 && inside.first() < inside.last())
        val finished = first.last().progress
        assertEquals(ReadMarkSource.READ_TO_END, finished.readMark?.source)
        assertEquals(1, finished.completeReads)
        assertEquals(BasisPoints.END, finished.points())
        val entered = saves.last { it.chapter == 1 }.progress
        assertNull(entered.readMark)
        // Just entered: far from the chapter's end.
        assertTrue("Fraction on entering chapter 2: ${entered.points()}", (entered.points() ?: -1) in 0 until 5_000)
        assertEquals(1, reading.current)
    }

    @Test
    fun `dado a leitura do capítulo 1 quando salto pelo sumário para o capítulo 5 então nenhum capítulo fica lido`() {
        open(opened = 0)
        compose.pageAction(R.string.reader_next_page)
        compose.waitUntil(15_000) { saves.isNotEmpty() }

        button(R.string.reader_chapters).performClick()
        compose.onNodeWithText("Capítulo 5").performClick()

        compose.waitUntil(15_000) { saves.any { it.chapter == 4 } }
        assertEquals(emptyList<Int>(), saves.filter { it.progress.readMark != null }.map { it.chapter })
        assertEquals(4, reading.current)
    }

    /** Next page until chapter [chapter] has a place past its start, and returns that place. */
    private fun readPartway(chapter: Int): Int {
        repeat(60) {
            saves.lastOrNull { it.chapter == chapter }?.progress?.points()?.takeIf { it > 0 }?.let { return it }
            compose.pageAction(R.string.reader_next_page)
            compose.waitForIdle()
            Thread.sleep(400)
        }
        throw AssertionError("No place past the start of chapter $chapter; saved: ${saves.map { it.chapter to it.progress.points() }}")
    }

    private fun awaitSaveIn(chapter: Int): SplitReading.Saved {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            saves.lastOrNull()?.takeIf { it.chapter == chapter }?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("No save in chapter $chapter; saved: ${saves.map { it.chapter to it.progress.points() }}")
    }

    // Gemini's review of slice 5, finding 2: the bar's chapter buttons went to the chapter's start
    // and saved it there, losing where the person had stopped in it (progress contract, C3).
    @Test
    fun `dado o capítulo 0 lido até o meio quando vou ao próximo pela barra e volto pelo anterior então ele reabre onde parei`() {
        open(0)
        val partway = readPartway(0)
        saves.clear()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_next_chapter)).performClick()
        awaitSaveIn(1)
        saves.clear()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_previous_chapter)).performClick()
        val back = awaitSaveIn(0)

        // Going to the kept place by its CFI lays the screen out from that passage, and the share of
        // the chapter, read at the screen's end, lands a little before (766 for 787 on the emulator,
        // run 45045711701); the start would be far behind (74 before the fix, run 45045551459).
        val points = back.progress.points() ?: 0
        assertTrue("Chapter 0 came back at $points, it was at $partway", points in (partway - 100)..(partway + 100))
    }

    // Gemini's review of slice 5, finding 3: in a split file, the chapter list handed every chapter to
    // the app to open, which did nothing for one in the same file.
    @Test
    fun `dado um romance num arquivo só quando escolho na lista um capítulo do mesmo arquivo então a página vai até ele`() {
        open(0, withChapterList = true)
        readInto(1)
        saves.clear()
        compose.onNodeWithText(compose.activity.getString(R.string.reader_chapters)).performClick()
        compose.onNodeWithText("Capítulo da lista 0").performClick()

        awaitSaveIn(0)
        assertTrue("The list handed the chapter to the app: $workChapterOpened", workChapterOpened.isEmpty())
    }
}
