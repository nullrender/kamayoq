package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, piece 23 (decision 9): going past the last page shows the end block, which marks the book
// read once per opening. The fraction never decides it.
@RunWith(AndroidJUnit4::class)
class TextReaderEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val saves = CopyOnWriteArrayList<ChapterProgress>()
    private val seen = CopyOnWriteArrayList<String>()

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "end.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
        compose.setContent {
            TextReader(
                archive,
                "Livro",
                TextReading(saved = null as TextPosition?, "emulator"),
                onSave = { saves += it },
                onBack = {},
                onMessage = { message ->
                    seen += message::class.simpleName.orEmpty()
                    messages += message
                },
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

    @After
    fun closeBook() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive.close()
        file.delete()
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    /** What the page says about where it stands, for a failure to explain itself. */
    // evaluateJavascript does not wait for a promise, so the probe leaves its answer in a global.
    private fun pageState(): String {
        evaluateJs(
            "globalThis.endProbe = null; (async () => { const r = document.querySelector('foliate-view').renderer; " +
                "const geometry = { page: r.page, pages: r.pages, start: r.start, end: r.end, size: r.size, viewSize: r.viewSize, " +
                "position: r.containerPosition, animated: r.hasAttribute('animated') }; " +
                "const turn = await Promise.race([r.next().then(() => 'ended'), new Promise(done => setTimeout(() => done('still waiting after 3 s'), 3000))]); " +
                "globalThis.endProbe = JSON.stringify({ ...geometry, directTurn: turn, after: { page: r.page, position: r.containerPosition } }) })(); null",
        )
        Thread.sleep(4_000)
        return evaluateJs("globalThis.endProbe") + " " + location()
    }

    private fun evaluateJs(script: String): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete)
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun location(): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(
                "(() => { const v = document.querySelector('foliate-view'); const l = v.lastLocation; " +
                    "return JSON.stringify({ cfi: l?.cfi, section: l?.section, fraction: l?.fraction, atEnd: v.renderer.atEnd, " +
                    "sections: v.book.sections.map(s => s.linear ?? 'yes'), " +
                    "after: (() => { const r = l?.range; if (!r) return null; const d = r.endContainer.ownerDocument; " +
                    "const rest = d.createRange(); rest.setStart(r.endContainer, r.endOffset); " +
                    "rest.setEnd(d.body, d.body.childNodes.length); return rest.toString().trim().slice(0, 120) })() }) })()",
                answer::complete,
            )
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun button(label: Int) = compose.onNodeWithText(compose.activity.getString(label))

    private fun endOfBook() = compose.activity.getString(R.string.end_of_book)

    private fun endBlockShows() = compose.onAllNodesWithText(endOfBook()).fetchSemanticsNodes().isNotEmpty()

    /**
     * The last section from the contents, then Next page until the end block shows. The short book
     * runs to about 90 pages on the emulator, 30 of them in the last section (emulator 44316557957):
     * reading it all from the start took more turns than the test allowed.
     */
    private fun readToTheEnd() {
        button(R.string.reader_contents).performClick()
        compose.onNodeWithText("Seção 3").performClick()
        compose.waitForIdle()
        Thread.sleep(1_000)
        repeat(60) {
            if (endBlockShows()) return
            compose.pageAction(R.string.reader_next_page)
            compose.waitForIdle()
            Thread.sleep(300)
        }
        throw AssertionError("No end block after 60 pages of the last section; page: ${pageState()}; messages seen: ${seen.distinct()}; last save: ${saves.lastOrNull()}")
    }

    @Test
    fun `dado a última página quando toco próxima então o bloco de fim aparece e o livro fica lido uma vez`() {
        readToTheEnd()

        compose.onNodeWithText(endOfBook()).assertExists()
        compose.waitUntil(15_000) { saves.lastOrNull()?.readMark != null }
        val read = saves.last()
        assertEquals(ReadMarkSource.READ_TO_END, read.readMark?.source)
        assertEquals(1, read.completeReads)
    }

    // Found by the Codex review of 18/11: a page under the block must take no touch and no TalkBack focus.
    @Test
    fun `dado o bloco de fim então a página de baixo fica escondida dos toques e do TalkBack`() {
        readToTheEnd()

        var visibility = View.VISIBLE
        compose.runOnUiThread { visibility = checkNotNull(compose.activity.window.decorView.findWebView()).visibility }
        assertEquals("The page under the end block is still shown", View.INVISIBLE, visibility)
    }

    @Test
    fun `dado o bloco de fim quando volto e avanço de novo então as leituras não somam outra vez`() {
        readToTheEnd()
        compose.waitUntil(15_000) { saves.lastOrNull()?.readMark != null }

        compose.pageAction(R.string.reader_previous_page)
        compose.waitUntil(15_000) { !endBlockShows() }
        compose.pageAction(R.string.reader_next_page)
        compose.waitUntil(15_000) { endBlockShows() }
        Thread.sleep(1_000)

        assertEquals(1, saves.maxOf { it.completeReads })
    }
}
