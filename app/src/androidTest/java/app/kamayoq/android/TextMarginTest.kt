package app.kamayoq.android

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.PageThemes
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.ShownLook
import app.kamayoq.core.reader.TextEdit
import app.kamayoq.core.reader.TextNumber
import app.kamayoq.core.reader.TextScope
import app.kamayoq.core.reader.TextSettings
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Gustavo's decision of 22/11/2026 (kamayoq-notes, research font-margin-defaults/apps-survey.md): the
// panel's margin is the margin a person sees between the text and each side of the page, in dp, as in
// most Android readers. A CSS pixel in the WebView is one dp.
@RunWith(AndroidJUnit4::class)
class TextMarginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var archive: EpubArchive? = null
    private lateinit var webView: WebView

    private fun shown(margin: Int): ShownLook {
        val settings = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.MARGIN, margin))
        return ShownLook(settings.effective(), PageThemes.colors(PageTheme.DARK, systemDark = true))
    }

    /** Opens the book; with [widthDp], in a WebView that wide, wider than the emulator's screen. */
    private fun open(look: ShownLook, flow: ReaderFlow, widthDp: Int? = null) {
        val file = File(instrumentation.targetContext.cacheDir, "margin.epub").apply { writeBytes(EpubFixtures.short()) }
        val opened = EpubArchive.open(file).also { archive = it }
        scenario = ActivityScenario.launch(ComponentActivity::class.java).apply {
            onActivity { activity ->
                webView = WebView(activity)
                if (widthDp == null) {
                    activity.setContentView(webView)
                } else {
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    activity.setContentView(FrameLayout(activity).apply { addView(webView, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)) })
                }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        console += "${message.messageLevel()}: ${message.message()}"
                        return true
                    }
                }
                webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(opened, token = "margin1", position = null, flow = flow, look = look)), messages::add)
            }
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> {
                    SystemClock.sleep(1_500)
                    return
                }
                is ReaderMessage.Failed, is ReaderMessage.Refused -> throw AssertionError("Reader said: $message; console: $console")
                else -> Unit
            }
        }
        throw AssertionError("No restore arrived; console: $console")
    }

    @After
    fun close() {
        scenario?.onActivity { webView.destroy() }
        scenario?.close()
        archive?.close()
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS).trim('"')
    }

    private class Edges(val left: Double, val right: Double, val columns: Int, val width: Double)

    // The paragraphs' boxes on screen, one per column they run through: the outermost ones are where
    // the text starts and ends. Boxes on the pages before and after sit past the screen's edges.
    private fun edges(): Edges {
        val answer = evaluate(
            "(() => { const renderer = document.querySelector('foliate-view').renderer; const width = innerWidth; " +
                "const boxes = renderer.getContents().flatMap(c => { const frame = c.doc.defaultView.frameElement.getBoundingClientRect(); " +
                "return [...c.doc.querySelectorAll('p')].flatMap(p => [...p.getClientRects()].map(b => [frame.left + b.left, frame.left + b.right])) }) " +
                ".filter(([left, right]) => right > 0 && left < width); " +
                "return [Math.min(...boxes.map(b => b[0])), width - Math.max(...boxes.map(b => b[1])), renderer.columnCount, width].join('|') })()",
        )
        val parts = answer.split('|').map { it.toDoubleOrNull() ?: throw AssertionError("Edges: $answer; console: $console") }
        return Edges(parts[0], parts[1], parts[2].toInt(), parts[3])
    }

    private fun assertMargin(expected: Int, edges: Edges, where: String) {
        Log.i("KamayoqMeasure", "$where: panel margin $expected, text at ${edges.left} dp from the left and ${edges.right} dp from the right, ${edges.columns} column(s), ${edges.width} dp wide")
        assertTrue("left ${edges.left} for $expected", abs(edges.left - expected) <= 1.0)
        assertTrue("right ${edges.right} for $expected", abs(edges.right - expected) <= 1.0)
    }

    @Test
    fun `dado a margem 16 em Páginas então o texto fica a 16 dp de cada lado`() {
        open(shown(16), ReaderFlow.PAGES)
        assertMargin(16, edges(), "Pages")
    }

    @Test
    fun `dado a margem 32 em Páginas então o texto fica a 32 dp de cada lado`() {
        open(shown(32), ReaderFlow.PAGES)
        assertMargin(32, edges(), "Pages")
    }

    @Test
    fun `dado a margem 16 em Rolar então o texto fica a 16 dp de cada lado`() {
        open(shown(16), ReaderFlow.SCROLL)
        assertMargin(16, edges(), "Scroll")
    }

    @Test
    fun `dado uma página adiante em Páginas então a margem continua a do painel`() {
        open(shown(16), ReaderFlow.PAGES)
        evaluate("document.querySelector('foliate-view').next(); null")
        SystemClock.sleep(1_500)
        assertMargin(16, edges(), "Pages, one page on")
    }

    @Test
    fun `dado uma tela larga com duas colunas então as bordas de fora ficam na margem do painel`() {
        open(shown(16), ReaderFlow.PAGES, widthDp = 900)
        val edges = edges()
        assertEquals("columns", 2, edges.columns)
        assertMargin(16, edges, "Two columns")
    }
}
