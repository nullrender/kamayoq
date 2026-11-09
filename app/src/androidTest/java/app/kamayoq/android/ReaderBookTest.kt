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
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// The first measurement of the fork in an Android WebView. It needs a real window: the paginator
// lays out by the size of the view, which is zero outside one.
@RunWith(AndroidJUnit4::class)
class ReaderBookTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var archive: EpubArchive
    private lateinit var webView: WebView

    @Before
    fun openBook() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fixture.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            webView = WebView(activity)
            activity.setContentView(webView)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console += "${message.messageLevel()}: ${message.message()}"
                    return true
                }
            }
            webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(archive, token = "abc123", position = null)), messages::add)
        }
    }

    @After
    fun closeBook() {
        scenario.onActivity { webView.destroy() }
        scenario.close()
        archive.close()
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun nextRelocated(seconds: Long = 60): ReaderMessage.Relocated? {
        while (true) {
            when (val message = messages.poll(seconds, TimeUnit.SECONDS)) {
                null -> return null
                is ReaderMessage.Relocated -> return message
                is ReaderMessage.Failed, is ReaderMessage.Refused, is ReaderMessage.Link -> fail("Reader said: $message; console: $console")
                ReaderMessage.Ready, ReaderMessage.Restored -> Unit
            }
        }
    }

    private fun firstRelocated() = nextRelocated() ?: throw AssertionError("No position arrived; console: $console")

    @Test
    fun `dado um livro quando abre então o texto usa tamanho dezoito entrelinha um e meio e recuo de parágrafo`() {
        firstRelocated()
        val styles = evaluate("""
            (() => {
                const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc
                const style = doc.defaultView.getComputedStyle(doc.querySelector('p'))
                return [style.fontSize, style.lineHeight, style.textIndent, style.textAlign, style.hyphens].join('|')
            })()
        """.trimIndent())
        assertEquals("\"18px|27px|27px|left|none\"", styles)
    }

    @Test
    fun `dado um livro quando abre então as margens do leitor são vinte e quatro pixels`() {
        firstRelocated()
        assertEquals("\"24px|24px|24px|24px\"", evaluate("""
            ['top', 'bottom', 'left', 'right'].map(side =>
                document.querySelector('foliate-view').renderer.getAttribute('margin-' + side)).join('|')
        """.trimIndent()))
    }

    @Test
    fun `dado a cor própria do livro quando o leitor aplica os padrões então a cor permanece`() {
        firstRelocated()
        assertEquals("\"rgb(23, 67, 89)\"", evaluate("""
            (() => {
                const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc
                const p = doc.querySelector('#book-color')
                return doc.defaultView.getComputedStyle(p).color
            })()
        """.trimIndent()))
    }

    private fun visibleText() = evaluate(
        "document.querySelector('foliate-view').renderer.getContents().map(c => c.doc.body.textContent).join(' ')",
    )

    @Test
    fun `dado um EPUB de três seções quando abro então o texto da primeira aparece e relocate traz seção CFI e fração`() {
        val first = firstRelocated()

        assertEquals(0, first.section)
        assertEquals(3, first.sections)
        assertTrue(first.cfi, first.cfi.startsWith("epubcfi("))
        assertTrue(visibleText(), "Seção 1" in visibleText())
    }

    @Test
    fun `dado o próximo comando quando repito então a seção muda`() {
        firstRelocated()

        // The paginator drops a turn that arrives while it is still busy with the last one (its
        // #locked flag), with no answer: a turn with no position in 5 s is sent again.
        var section = 0
        for (turn in 1..60) {
            instrumentation.runOnMainSync { webView.goToNextPage() }
            section = nextRelocated(seconds = 5)?.section ?: continue
            if (section == 1) break
        }

        assertEquals("console: $console", 1, section)
        assertTrue(visibleText(), "Seção 2" in visibleText())
    }

    // Piece 10 took allow-scripts out of the book's frame, and the fork's comment says WebKit needs
    // it for events: the turn by finger is what the design asked to measure.
    @Test
    fun `dado o texto do livro quando deslizo o dedo da direita para a esquerda então a página vira`() {
        val first = firstRelocated()
        SystemClock.sleep(2_000)

        val location = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            webView.getLocationOnScreen(location)
            width = webView.width
            height = webView.height
        }
        val y = location[1] + height / 2f
        // The same lost-touch allowance as the links: at most 3 swipes.
        var turned: ReaderMessage.Relocated? = null
        for (attempt in 1..3) {
            swipeScreen(location[0] + width * 0.8f to y, location[0] + width * 0.2f to y)
            turned = nextRelocated(seconds = 5)?.takeIf { it.cfi != first.cfi }
            if (turned != null) break
        }

        assertTrue("No turn after a swipe; console: $console", turned != null)
    }
}
