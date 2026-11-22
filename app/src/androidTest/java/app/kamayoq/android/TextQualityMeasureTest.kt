package app.kamayoq.android

import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.PageLook
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.PageThemes
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: the measures PERSONALITY, principle 7, asks the implementation to check, at the default
// font and size, kept in the log as KamayoqMeasure. They measure what the WebView and foliate-js do
// with the look, so they go in green: a red here would be staged (AGENTS.md).
@RunWith(AndroidJUnit4::class)
class TextQualityMeasureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var archive: EpubArchive? = null
    private lateinit var webView: WebView

    private fun shown(settings: TextSettings = TextSettings.DEFAULT) =
        ShownLook(settings.effective(), PageThemes.colors(PageTheme.DARK, systemDark = true))

    private fun open(look: ShownLook) {
        val file = File(instrumentation.targetContext.cacheDir, "measure.epub").apply { writeBytes(EpubFixtures.short()) }
        val opened = EpubArchive.open(file).also { archive = it }
        scenario = ActivityScenario.launch(ComponentActivity::class.java).apply {
            onActivity { activity ->
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        console += "${message.messageLevel()}: ${message.message()}"
                        return true
                    }
                }
                webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(opened, token = "measure1", position = null, look = look)), messages::add)
            }
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> {
                    // The reading font lays the page out again when it arrives.
                    evaluate("document.querySelector('foliate-view').renderer.getContents()[0].doc.fonts.ready.then(() => globalThis.fontsIn = true); null")
                    val fonts = SystemClock.uptimeMillis() + 10_000
                    while (SystemClock.uptimeMillis() < fonts && evaluate("String(globalThis.fontsIn === true)") != "\"true\"") SystemClock.sleep(200)
                    SystemClock.sleep(1_000)
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
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun inBook(expression: String) =
        evaluate("(() => { const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc; return String($expression) })()").trim('"')

    @Test
    fun `dado a letra e o tamanho padrão então a linha tem de 35 a 50 caracteres a letra 16 px ou mais e a entrelinha de 1 4 a 1 6`() {
        open(shown())
        // The characters of the second line of the first paragraph, one by one by where each sits:
        // the first line is shorter by the indent.
        val perLine = inBook(
            "(() => { const text = doc.querySelector('p').firstChild; const r = doc.createRange(); " +
                "const topAt = i => { r.setStart(text, i); r.setEnd(text, i + 1); const rect = r.getClientRects()[0]; return rect ? Math.round(rect.top) : null }; " +
                "let i = 0; const first = topAt(0); while (i < text.length && topAt(i) === first) i++; " +
                "const start = i, second = topAt(start); while (i < text.length && topAt(i) === second) i++; " +
                "return i - start })()",
        ).toDouble()
        val style = inBook("(() => { const s = doc.defaultView.getComputedStyle(doc.querySelector('p')); return s.fontSize + '|' + s.lineHeight + '|' + doc.defaultView.innerWidth })()")
        val (size, line, width) = style.split('|')
        val fontPx = size.removeSuffix("px").toDouble()
        val ratio = line.removeSuffix("px").toDouble() / fontPx
        Log.i("KamayoqMeasure", "Default look: $perLine characters per line, $size, line height $ratio, frame $width px wide")

        assertTrue("$perLine characters per line", perLine in 35.0..50.0)
        assertTrue("font $size", fontPx >= 16)
        assertTrue("line height $ratio", ratio in 1.4..1.6)
    }

    @Test
    fun `dado os espaçamentos do WCAG 1 4 12 então nenhum parágrafo sai da largura da página`() {
        open(shown())
        // WCAG 1.4.12: line height 1.5, paragraph spacing 2 em, letter 0.12 em, word 0.16 em.
        val result = inBook(
            "(() => { const style = doc.createElement('style'); " +
                "style.textContent = 'p { line-height: 1.5 !important; margin-bottom: 2em !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; }'; " +
                "doc.head.append(style); const ps = [...doc.querySelectorAll('p')]; " +
                "const clipped = ps.filter(p => p.scrollWidth > p.clientWidth + 1).length; " +
                "style.remove(); return clipped })()",
        )
        Log.i("KamayoqMeasure", "WCAG 1.4.12 spacing: paragraphs wider than the page = $result")
        assertEquals("0", result)
    }

    @Test
    fun `dado uma página adiante quando a letra cresce então o trecho do começo da página continua na tela`() {
        open(shown())
        repeat(2) {
            evaluate("document.querySelector('foliate-view').next(); null")
            SystemClock.sleep(1_500)
        }
        evaluate("globalThis.anchor = document.querySelector('foliate-view').lastLocation.range.cloneRange(); null")
        val bigger = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.SIZE, 24))
        instrumentation.runOnMainSync { webView.applyLook(PageLook.command(shown(bigger))) }
        SystemClock.sleep(3_000)

        val kept = evaluate(
            "(() => { const range = document.querySelector('foliate-view').lastLocation.range; " +
                "return String(range.comparePoint(anchor.startContainer, anchor.startOffset) === 0) })()",
        )
        Log.i("KamayoqMeasure", "Start of the page still on screen after a larger font: $kept")
        assertEquals("\"true\"", kept)
    }
}
