package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun writeBook() {
        file = File(compose.activity.cacheDir, "split.epub").apply { writeBytes(EpubFixtures.novel()) }
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

    /** Opens the novel at the chapter of place [opened] in the file, never read, and waits for the restore. */
    private fun open(opened: Int) {
        reading = SplitReading(List(chapters.keys.size) { ChapterProgress.NEVER_OPENED }, opened, "emulator")
        val session = SplitText(reading, chapters) { saves += it }
        compose.setContent {
            TextReader(archive, "Novela em volumes", session, onBack = {}, onMessage = messages::add, type = WorkType.NOVELS, end = TextEnd.Chapter(null) {})
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

    private fun visibleText(): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView())
                .evaluateJavascript("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''", answer::complete)
        }
        return JSONTokener(answer.get(30, TimeUnit.SECONDS)).nextValue() as String
    }

    private fun button(label: Int) = compose.onNodeWithText(compose.activity.getString(label))

    /** Next page until a place is saved in the chapter of place [chapter], as a person reading on. */
    private fun readInto(chapter: Int) {
        repeat(80) {
            if (saves.any { it.chapter == chapter }) return
            button(R.string.reader_next_page).performClick()
            compose.waitForIdle()
            Thread.sleep(400)
        }
        throw AssertionError("No place saved in chapter $chapter after 80 screens; saved: ${saves.map { it.chapter to (it.progress.position as? TextPosition)?.fraction?.value }}")
    }

    private fun ChapterProgress.points() = (position as? TextPosition)?.fraction?.value

    @Test
    fun `dado o capítulo 4 nunca aberto quando o arquivo abre nele então a tela mostra o começo do capítulo 4`() {
        open(opened = 3)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var shown = visibleText()
        while (!shown.contains("Capítulo 4") && System.nanoTime() < deadline) {
            Thread.sleep(500)
            shown = visibleText()
        }
        assertTrue("The screen shows: ${shown.take(200)}", shown.contains("Capítulo 4"))
        // The first words of chapter 4, and none of the cover.
        assertTrue("The screen shows: ${shown.take(200)}", shown.contains("m4n1 "))
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
        button(R.string.reader_next_page).performClick()
        compose.waitUntil(15_000) { saves.isNotEmpty() }

        button(R.string.reader_contents).performClick()
        compose.onNodeWithText("Capítulo 5").performClick()

        compose.waitUntil(15_000) { saves.any { it.chapter == 4 } }
        assertEquals(emptyList<Int>(), saves.filter { it.progress.readMark != null }.map { it.chapter })
        assertEquals(4, reading.current)
    }
}
