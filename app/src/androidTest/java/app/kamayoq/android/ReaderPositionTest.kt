package app.kamayoq.android

import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.RelocateReason
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Piece 14: a CFI the fork gave is a promise to show the same words again. The prototype of 22/10
// failed it on the first round with a real book, so each way of reopening is measured here.
@RunWith(AndroidJUnit4::class)
class ReaderPositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val console = CopyOnWriteArrayList<String>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var archive: EpubArchive
    private var openings = 0

    private fun open(book: ByteArray) {
        val file = File(instrumentation.targetContext.cacheDir, "position.epub").apply { writeBytes(book) }
        archive = EpubArchive.open(file)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun closeBook() {
        scenario.close()
        archive.close()
    }

    /** One WebView on screen with the book, from `ready` to the confirmed restore. */
    private inner class Reader(position: String?, flow: ReaderFlow = ReaderFlow.PAGES) {
        val log = CopyOnWriteArrayList<ReaderMessage>()
        private val messages = LinkedBlockingQueue<ReaderMessage>()
        private lateinit var webView: WebView

        init {
            openings++
            scenario.onActivity { activity ->
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        console += "${message.messageLevel()}: ${message.message()}"
                        return true
                    }
                }
                val book = ReaderBook(archive, token = "position$openings", position, flow)
                webView.loadReaderPage(ReaderClient(activity.assets, book)) { message ->
                    log += message
                    messages += message
                }
            }
            while (true) {
                val message = next(seconds = 60) ?: throw AssertionError("No restore in 60 s; console: $console")
                if (message == ReaderMessage.Restored) break
            }
        }

        private fun next(seconds: Long): ReaderMessage? {
            val message = messages.poll(seconds, TimeUnit.SECONDS) ?: return null
            if (message is ReaderMessage.Failed || message is ReaderMessage.Refused || message is ReaderMessage.Link) {
                throw AssertionError("Reader said: $message; console: $console")
            }
            return message
        }

        fun evaluate(script: String): String {
            val answer = CompletableFuture<String>()
            instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
            return answer.get(30, TimeUnit.SECONDS)
        }

        /**
         * Runs [script] until a position with [reason] comes back. The paginator drops a move that
         * arrives while it is busy (its #locked flag), with no answer, so a silent move is sent again.
         */
        fun move(script: String, reason: RelocateReason): ReaderMessage.Relocated {
            repeat(12) {
                evaluate("$script; null")
                while (true) {
                    val message = next(seconds = 5) ?: break
                    if (message is ReaderMessage.Relocated && message.reason == reason) return message
                }
            }
            throw AssertionError("No ${reason.wire} move after 12 tries; console: $console")
        }

        fun goTo(href: String) = move("document.querySelector('foliate-view').goTo('$href')", RelocateReason.NAVIGATION)

        fun nextPage() = move("kamayoqReader.next()", RelocateReason.PAGE)

        /** The text the fork says is on screen now. */
        fun visibleText(): String =
            JSONArray("[" + evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''") + "]").getString(0)

        /** The first whole word on screen: a word cut at the start of a page is not whole. */
        fun firstWord(): String = WORD.find(visibleText())?.value ?: throw AssertionError("No word on screen: ${visibleText().take(200)}")

        /**
         * Waits until the reader has said nothing for a second. `restored` comes before the fork
         * finishes filling the screen around the page, and its last re-anchor comes after.
         */
        fun settle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var seen = log.size
            while (System.nanoTime() < deadline) {
                Thread.sleep(1_000)
                if (log.size == seen) return
                seen = log.size
            }
        }

        /**
         * Waits for a re-layout to replace the page the fork reports: until then it still reports the
         * old page, whose text does not change with the layout.
         */
        fun awaitNewPage(before: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                if (visibleText() != before) return
                Thread.sleep(500)
            }
            throw AssertionError("No re-layout replaced the page; console: $console")
        }

        /** Shows [word] once the reader settles, or fails saying what is there. */
        fun assertShows(word: String) {
            settle()
            val whole = Regex("(?<![\\w])$word(?![\\w])")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                if (whole.containsMatchIn(visibleText())) return
                Thread.sleep(500)
            }
            throw AssertionError(
                "\"$word\" is not on screen; the screen starts with: ${visibleText().take(200)}; moves after the restore: ${moves()}; console: $console",
            )
        }

        /** The moves after the restore, which say whether a re-layout passed itself off as the person. */
        fun moves(): List<String> = log.dropWhile { it != ReaderMessage.Restored }
            .filterIsInstance<ReaderMessage.Relocated>()
            .map { "${it.reason.wire} ${it.position.value} ${it.cfi}" }

        /** One finger stroke up the middle of the book, the way a person scrolls down in Scroll. */
        fun swipeUp() {
            // What came before the stroke must not be read as its answer.
            messages.clear()
            val location = IntArray(2)
            var width = 0
            var height = 0
            instrumentation.runOnMainSync {
                webView.getLocationOnScreen(location)
                width = webView.width
                height = webView.height
            }
            val x = location[0] + width / 2f
            swipeScreen(x to location[1] + height * 0.7f, x to location[1] + height * 0.3f)
        }

        /** The next move the guard would take as the person's, if one comes within [seconds]. */
        fun awaitPersonMove(seconds: Long): ReaderMessage.Relocated? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                val message = next(seconds = 1) ?: continue
                if (message is ReaderMessage.Relocated && message.reason.byPerson) return message
            }
            return null
        }

        fun close() = scenario.onActivity { activity ->
            activity.setContentView(View(activity))
            webView.destroy()
        }
    }

    private class Saved(val position: ReaderMessage.Relocated, val word: String) {
        fun asTextPosition() = TextPosition(position.section, position.sections, position.cfi, position.position, Instant.EPOCH, "emulator")
    }

    /** What the app's guard keeps after every message this reader sent so far. */
    private fun Reader.guardedFrom(saved: Saved): TextReading {
        val reading = TextReading(saved.asTextPosition(), "emulator")
        for (message in log) {
            when (message) {
                ReaderMessage.Restored -> reading.restored()
                is ReaderMessage.Relocated -> reading.relocated(message, Instant.now())
                else -> Unit
            }
        }
        return reading
    }

    /**
     * Reads to a place the way a person would (a jump, then two pages), and closes the book there.
     * [href] is the path inside the archive: the fork resolves a bare file name to nothing.
     */
    private fun readTo(href: String): Saved {
        val reader = Reader(position = null)
        reader.goTo(href)
        reader.nextPage()
        val position = reader.nextPage()
        val saved = Saved(position, reader.firstWord())
        reader.close()
        return saved
    }

    @Test
    fun `dado um CFI salvo quando reabro então as mesmas palavras estão visíveis`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")

        val reader = Reader(position = saved.position.cfi)

        reader.assertShows(saved.word)
    }

    // An XHTML parser and an HTML parser (the frame's srcdoc) build these two differently: the empty
    // anchor wraps what follows, and the table gains a tbody.
    @Test
    fun `dado âncora vazia e tabela sem tbody antes do trecho quando reabro então o CFI aponta o mesmo trecho`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s2.xhtml#parte-2-1")

        val reader = Reader(position = saved.position.cfi)

        reader.assertShows(saved.word)
    }

    @Test
    fun `dado um CFI salvo quando reabro com a fonte maior então as mesmas palavras continuam visíveis e a posição salva fica`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")

        val reader = Reader(position = saved.position.cfi)
        reader.settle()
        val before = reader.visibleText()
        reader.evaluate("document.querySelector('foliate-view').renderer.setStyles('html { font-size: 150% !important }'); null")

        reader.awaitNewPage(before)
        reader.assertShows(saved.word)
        val size = reader.evaluate(
            "getComputedStyle(document.querySelector('foliate-view').renderer.getContents()[0].doc.documentElement).fontSize",
        )
        assertTrue("The font did not grow: $size", size.trim('"').removeSuffix("px").toFloat() > 16f)
        // The re-layout moved the page, not the person: the guard keeps the saved place.
        val reading = reader.guardedFrom(saved)
        assertEquals(saved.asTextPosition(), reading.position)
    }

    @Test
    fun `dado uma seção cortada em pedaços quando reabro então as mesmas palavras estão visíveis`() {
        open(EpubFixtures.positionsCompiled())
        val saved = readTo("OEBPS/s1.xhtml#cap-30")

        val reader = Reader(position = saved.position.cfi)

        reader.assertShows(saved.word)
    }

    // Switching the flow of an open book showed a place about 150 words earlier (emulator,
    // 03/11/2026), so a new flow reopens the book at the saved CFI.
    @Test
    fun `dado um CFI salvo em Páginas quando reabro em Rolar então as mesmas palavras estão visíveis`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")

        val reader = Reader(position = saved.position.cfi, flow = ReaderFlow.SCROLL)

        assertEquals("\"scrolled\"", reader.evaluate("document.querySelector('foliate-view').renderer.getAttribute('flow')"))
        reader.assertShows(saved.word)
    }

    @Test
    fun `dado o livro em Rolar quando viro a página pelo app então a posição nova chega como movimento da pessoa`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")
        val reader = Reader(position = saved.position.cfi, flow = ReaderFlow.SCROLL)

        val turned = reader.nextPage()

        assertTrue(turned.reason.byPerson)
        assertTrue("The turn did not move: ${turned.cfi}", turned.cfi != saved.position.cfi)
    }

    // In Scroll the fork reports every settled scroll as "scroll", whoever moved it, and it loads the
    // sections around the page after the restore: the guard must not take that loading for the person.
    @Test
    fun `dado o livro reaberto em Rolar quando ninguém toca então a posição salva fica`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")
        val reader = Reader(position = saved.position.cfi, flow = ReaderFlow.SCROLL)

        reader.settle()
        Thread.sleep(3_000)
        reader.settle()

        assertEquals("moves after the restore: ${reader.moves()}", saved.asTextPosition(), reader.guardedFrom(saved).position)
    }

    // The fork lets the first scroll after a jump pass silently (its #justAnchored flag).
    @Test
    fun `dado o livro reaberto em Rolar quando rolo com o dedo uma vez então a posição nova é aceita`() {
        open(EpubFixtures.positions())
        val saved = readTo("OEBPS/s3.xhtml#parte-3-1")
        val reader = Reader(position = saved.position.cfi, flow = ReaderFlow.SCROLL)
        reader.settle()

        reader.swipeUp()

        assertTrue("The first scroll was not taken; moves after the restore: ${reader.moves()}", reader.awaitPersonMove(seconds = 5) != null)
        assertEquals(TextReading.Phase.READING, reader.guardedFrom(saved).phase)
    }

    private companion object {
        val WORD = Regex("(?<![\\w])m\\d+n\\d+(?![\\w])")
    }
}
