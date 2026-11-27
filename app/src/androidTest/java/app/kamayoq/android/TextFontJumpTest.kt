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
import app.kamayoq.core.reader.PageLook
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.PageThemes
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

// The S24 defect of 22/11/2026, as it was measured on the phone on 27/11/2026 (kamayoq-notes, slice 5
// plan): Balada de amor ao vento reopened two pages into a chapter, with the long chapter before it
// loaded, on a 384 dp screen; choosing EB Garamond moved the kept place two pages on, and during the
// re-layout the page reported a place in the chapter before. The book here has an OpenType font with
// CFF outlines as that one has (Source Serif 4, SIL OFL 1.1, in the test APK only).
@RunWith(AndroidJUnit4::class)
class TextFontJumpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val console = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var archive: EpubArchive? = null
    private lateinit var webView: WebView

    private fun shown(settings: TextSettings) = ShownLook(settings.effective(), PageThemes.colors(PageTheme.DARK, systemDark = true))

    private fun open(file: File, position: String?) {
        messages.clear()
        val opened = EpubArchive.open(file).also { archive = it }
        scenario = ActivityScenario.launch(ComponentActivity::class.java).apply {
            onActivity { activity ->
                webView = WebView(activity)
                val width = (S24_WIDTH_DP * activity.resources.displayMetrics.density).toInt()
                activity.setContentView(FrameLayout(activity).apply { addView(webView, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)) })
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        console += "${message.messageLevel()}: ${message.message()}"
                        return true
                    }
                }
                val look = shown(TextSettings.DEFAULT)
                webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(opened, token = "fontjump1", position = position, look = look)), messages::add)
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

    private fun closeBook() {
        scenario?.onActivity { webView.destroy() }
        scenario?.close()
        scenario = null
        archive?.close()
        archive = null
    }

    @After
    fun close() = closeBook()

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS).trim('"')
    }

    private fun turn() {
        evaluate("document.querySelector('foliate-view').next(); null")
        SystemClock.sleep(700)
    }

    private fun section() = evaluate("String(document.querySelector('foliate-view').renderer.primaryIndex)")

    @Test
    fun `dado um livro com letra própria reaberto na terceira página de um capítulo com o anterior carregado quando escolho a Garamond então o lugar guardado não anda`() {
        val font = instrumentation.context.assets.open("fonts/SourceSerif4-Regular.otf").use { it.readBytes() }
        val file = File(instrumentation.targetContext.cacheDir, "font-jump.epub")
            .apply { writeBytes(EpubFixtures.ownFont(font, openType = true, firstChapter = 40)) }

        // Read into the second chapter's third page, and keep the place, as the app does when it closes.
        open(file, position = null)
        var turns = 0
        while (section() != "1") {
            if (++turns > 120) throw AssertionError("Never reached the second chapter; console: $console")
            turn()
        }
        repeat(2) { turn() }
        val kept = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        closeBook()

        open(file, position = kept)
        evaluate(
            "(() => { const view = document.querySelector('foliate-view'); const log = globalThis.probe = []; " +
                "view.renderer.addEventListener('relocate', ({ detail }) => log.push(detail.reason + ' ' + (detail.range?.toString().slice(0, 30) ?? ''))); " +
                "globalThis.anchor = view.lastLocation.range.cloneRange(); return null })()",
        )
        val loaded = evaluate("String(document.querySelector('foliate-view').renderer.getContents().map(c => c.index).join(','))")
        val before = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        val top = evaluate("anchor.toString().slice(0, 60)")

        val chosen = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Font(ReadingFont.GARAMOND))
        instrumentation.runOnMainSync { webView.applyLook(PageLook.command(shown(chosen))) }
        SystemClock.sleep(6_000)

        val after = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        val still = evaluate(
            "(() => { const range = document.querySelector('foliate-view').lastLocation.range; " +
                "return String(range.comparePoint(anchor.startContainer, anchor.startOffset) === 0) })()",
        )
        val now = evaluate("document.querySelector('foliate-view').lastLocation.range.toString().slice(0, 60)")
        val events = evaluate("JSON.stringify(globalThis.probe)")
        Log.i(
            "KamayoqMeasure",
            "Font jump, S24 replica after $turns turns: sections loaded $loaded; kept $kept; before $before \"$top\"; " +
                "after $after \"$now\"; start still shows $still; events $events",
        )
        assertEquals("before $before \"$top\", after $after \"$now\"; events $events", "true", still)
    }

    private companion object {
        const val S24_WIDTH_DP = 384
    }
}
