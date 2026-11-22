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
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.ReadingFont
import app.kamayoq.core.reader.ShownLook
import app.kamayoq.core.reader.TextEdit
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

// Slice 5, found on the S24 on 22/11/2026 (kamayoq-notes, docs/tests/ui-slice-5-s24-plan-2026-11-22.md):
// in a bought ebook with its own font, choosing one of the app's fonts moved the page about two
// paragraphs on. The book here brings its font in its stylesheet, with its own line height, spacing,
// indent and margins, and the reading goes into its second chapter by turning pages, with the first
// still loaded before it.
@RunWith(AndroidJUnit4::class)
class TextOwnFontTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var archive: EpubArchive? = null
    private lateinit var webView: WebView

    private fun shown(settings: TextSettings) = ShownLook(settings.effective(), PageThemes.colors(PageTheme.DARK, systemDark = true))

    private fun open(flow: ReaderFlow) {
        val context = instrumentation.targetContext
        val font = context.assets.open("reader/fonts/EBGaramond.ttf").use { it.readBytes() }
        val file = File(context.cacheDir, "own-font.epub").apply { writeBytes(EpubFixtures.ownFont(font)) }
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
                val look = shown(TextSettings.DEFAULT)
                webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(opened, token = "ownfont1", position = null, flow = flow, look = look)), messages::add)
            }
        }
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> {
                    SystemClock.sleep(2_000)
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

    private fun bookFontShows() = evaluate(
        "(() => { const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc; " +
            "const loaded = [...doc.fonts].some(face => face.family.replace(/['\"]/g, '') === 'Letra do livro' && face.status === 'loaded'); " +
            "return String(loaded && doc.defaultView.getComputedStyle(doc.querySelector('p')).fontFamily.includes('Letra do livro')) })()",
    )

    // The text of the page's start, and whether that place is still inside what shows.
    private fun startStillShows(): String = evaluate(
        "(() => { const range = document.querySelector('foliate-view').lastLocation.range; " +
            "return String(range.comparePoint(anchor.startContainer, anchor.startOffset) === 0) })()",
    )

    private fun choosingFontKeepsThePlace(flow: ReaderFlow, where: String) {
        open(flow)
        assertEquals("the book's own font shows; console: $console", "true", bookFontShows())
        // Into the second chapter by turning pages, as a person reads: the first one stays loaded before it.
        var turns = 0
        while (evaluate("String(document.querySelector('foliate-view').renderer.primaryIndex)") != "1") {
            if (++turns > 60) throw AssertionError("Never reached the second chapter; console: $console")
            evaluate("document.querySelector('foliate-view').next(); null")
            SystemClock.sleep(700)
        }
        repeat(2) {
            evaluate("document.querySelector('foliate-view').next(); null")
            SystemClock.sleep(1_500)
        }
        val loaded = evaluate("String(document.querySelector('foliate-view').renderer.getContents().map(c => c.index).join(','))")
        evaluate("globalThis.anchor = document.querySelector('foliate-view').lastLocation.range.cloneRange(); null")
        val before = evaluate("anchor.toString().slice(0, 60)")
        val chosen = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Font(ReadingFont.LITERATA))
        instrumentation.runOnMainSync { webView.applyLook(PageLook.command(shown(chosen))) }
        SystemClock.sleep(4_000)
        val kept = startStillShows()
        val after = evaluate("document.querySelector('foliate-view').lastLocation.range.toString().slice(0, 60)")
        Log.i("KamayoqMeasure", "$where, book with its own font, Literata chosen in the second chapter after $turns turns (sections loaded: $loaded): start kept $kept; before \"$before\", now \"$after\"")
        assertEquals("before \"$before\", now \"$after\"", "true", kept)
    }

    @Test
    fun `dado um livro com letra própria em Páginas quando escolho uma letra do app então o trecho do começo da página continua na tela`() =
        choosingFontKeepsThePlace(ReaderFlow.PAGES, "Pages")

    @Test
    fun `dado um livro com letra própria em Rolar quando escolho uma letra do app então o trecho do começo da tela continua na tela`() =
        choosingFontKeepsThePlace(ReaderFlow.SCROLL, "Scroll")
}
