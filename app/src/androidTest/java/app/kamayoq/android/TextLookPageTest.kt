package app.kamayoq.android

import android.os.SystemClock
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
import app.kamayoq.core.reader.ReadingFont
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
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: the settings panel's look in the real WebView. The defaults stay under the book's own
// styles; only what the person chose wins over them (PERSONALITY, principle 7).
@RunWith(AndroidJUnit4::class)
class TextLookPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var archive: EpubArchive? = null
    private lateinit var webView: WebView

    private fun shown(vararg edits: TextEdit, theme: PageTheme = PageTheme.DARK): ShownLook {
        var settings = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Theme(theme))
        for (edit in edits) settings = settings.change(TextScope.ALL_TEXT_WORKS, edit)
        return ShownLook(settings.effective(), PageThemes.colors(theme, systemDark = true))
    }

    private fun open(look: ShownLook) {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "styled.epub").apply { writeBytes(EpubFixtures.styled()) }
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
                webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(opened, token = "look1", position = null, look = look)), messages::add)
            }
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> return
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

    /** [expression] of the first section's document, as `doc`, until it gives [expected] or 10 s pass. */
    private fun assertInBook(expected: String, expression: String) {
        val script = "(() => { const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc; return String($expression) })()"
        var answer = ""
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            answer = evaluate(script)
            if (answer == "\"$expected\"") return
            SystemClock.sleep(250)
        }
        assertEquals("console: $console", "\"$expected\"", answer)
    }

    private fun style(selector: String, property: String) =
        "doc.defaultView.getComputedStyle(doc.querySelector('$selector')).$property"

    @Test
    fun `dado o tamanho escolhido quando o livro abre então o texto corrido tem vinte e dois pixels`() {
        open(shown(TextEdit.Number(TextNumber.SIZE, 22)))
        assertInBook("22px", style("p", "fontSize"))
    }

    @Test
    fun `dado a letra Garamond escolhida quando o livro abre então até a letra própria do livro vira Garamond carregada do aplicativo`() {
        open(shown(TextEdit.Font(ReadingFont.GARAMOND)))
        assertInBook("\\\"EB Garamond\\\", serif", style(".book-font", "fontFamily"))
        assertInBook("true", "[...doc.fonts].some(face => face.family.includes('EB Garamond') && face.status === 'loaded')")
    }

    @Test
    fun `dado a letra nunca escolhida quando o livro abre então a letra própria do livro permanece`() {
        open(shown())
        assertInBook("monospace", style(".book-font", "fontFamily"))
    }

    @Test
    fun `dado justificar escolhido quando o livro abre então o texto justifica e a cena que o livro centra continua centrada`() {
        open(shown(TextEdit.Justify(true)))
        assertInBook("justify", style("p", "textAlign"))
        assertInBook("center", style(".scene", "textAlign"))
        assertInBook("0px", style(".scene", "textIndent"))
    }

    @Test
    fun `dado o tema sépia quando o livro abre então a página e o texto sem cor própria ficam nas cores do tema`() {
        open(shown(theme = PageTheme.SEPIA))
        assertInBook("rgb(239, 228, 204)", "doc.defaultView.getComputedStyle(doc.body).backgroundColor")
        assertInBook("rgb(58, 46, 32)", style("p", "color"))
        assertInBook("rgb(239, 228, 204)", "getComputedStyle(document.body).backgroundColor")
    }

    @Test
    fun `dado as margens escolhidas quando o livro abre então as laterais são trinta e dois pixels`() {
        open(shown(TextEdit.Number(TextNumber.MARGIN, 32)))
        assertInBook("32px|32px", "['left', 'right'].map(side => document.querySelector('foliate-view').renderer.getAttribute('margin-' + side)).join('|')")
    }

    @Test
    fun `dado o livro aberto quando a aparência muda então o tamanho muda sem reabrir a página`() {
        open(shown())
        evaluate("globalThis.samePage = 'yes'")
        instrumentation.runOnMainSync { webView.applyLook(PageLook.command(shown(TextEdit.Number(TextNumber.SIZE, 24)))) }
        assertInBook("24px", style("p", "fontSize"))
        assertInBook("yes", "globalThis.samePage")
    }
}
