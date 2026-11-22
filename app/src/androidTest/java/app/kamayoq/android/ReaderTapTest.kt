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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: a real finger on the book reaches the app as a tap with its place across the page, and a
// touch on a link stays a link.
@RunWith(AndroidJUnit4::class)
class ReaderTapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Session(val webView: WebView, val messages: LinkedBlockingQueue<ReaderMessage>, val console: List<String>)

    private fun withBook(epub: ByteArray, read: (Session) -> Unit) {
        val file = File(instrumentation.targetContext.cacheDir, "taps.epub").apply { writeBytes(epub) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var session: Session
                scenario.onActivity { activity ->
                    val webView = WebView(activity)
                    val console = CopyOnWriteArrayList<String>()
                    activity.setContentView(webView)
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            console += "${message.messageLevel()}: ${message.message()}"
                            return true
                        }
                    }
                    webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(archive, token = "taps1", position = null)), messages::add)
                    session = Session(webView, messages, console)
                }
                try {
                    session.nextMessage { it == ReaderMessage.Restored } ?: error("The book did not open; console: ${session.console}")
                    read(session)
                } finally {
                    scenario.onActivity { session.webView.destroy() }
                }
            }
        }
    }

    private fun Session.nextMessage(seconds: Long = 60, predicate: (ReaderMessage) -> Boolean): ReaderMessage? {
        val deadline = SystemClock.uptimeMillis() + seconds * 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            val message = messages.poll(1, TimeUnit.SECONDS) ?: continue
            if (predicate(message)) return message
        }
        return null
    }

    /** A point on the page at [across] of its width, halfway down. */
    private fun Session.pointAt(across: Float): Pair<Float, Float> {
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        return location[0] + webView.width * across to location[1] + webView.height / 2f
    }

    /** Touches until a tap arrives, at most 3 times: a touch right after opening is sometimes lost on the emulator. */
    private fun Session.tapAt(across: Float): ReaderMessage.Tap? {
        repeat(3) {
            touchScreen(pointAt(across))
            (nextMessage(seconds = 3) { it is ReaderMessage.Tap } as ReaderMessage.Tap?)?.let { return it }
        }
        return null
    }

    @Test
    fun `dado um toque na direita da página então o app recebe um toque perto da borda direita`() {
        withBook(EpubFixtures.short()) { session ->
            val tap = session.tapAt(0.85f)
            assertNotNull("No tap arrived; console: ${session.console}", tap)
            assertTrue("x = ${tap?.x}", tap!!.x in 0.8..0.9)
        }
    }

    @Test
    fun `dado um toque no meio da página então o app recebe um toque no meio`() {
        withBook(EpubFixtures.short()) { session ->
            val tap = session.tapAt(0.5f)
            assertNotNull("No tap arrived; console: ${session.console}", tap)
            assertTrue("x = ${tap?.x}", tap!!.x in 0.45..0.55)
        }
    }

    @Test
    fun `dado um toque num link então chega o link e nenhum toque de página`() {
        withBook(EpubFixtures.links()) { session ->
            var link: ReaderMessage? = null
            repeat(3) {
                if (link == null) {
                    session.messages.clear()
                    touchScreen(session.webView.screenPointOf("#out"))
                    link = session.nextMessage(seconds = 3) { it is ReaderMessage.Link }
                }
            }
            assertNotNull("No link arrived; console: ${session.console}", link)
            assertNull(session.messages.firstOrNull { it is ReaderMessage.Tap })
            assertNull(session.nextMessage(seconds = 2) { it is ReaderMessage.Tap })
        }
    }

    // Gemini's review of slice 5, finding 8: only the section's frames listened, so a touch on the page
    // around them (below a short chapter in Scroll, the sides of a wide screen) did nothing.
    @Test
    fun `dado um toque na página fora do quadro da seção então o app recebe o toque`() {
        withBook(EpubFixtures.short()) { session ->
            instrumentation.runOnMainSync {
                session.webView.evaluateJavascript(
                    "document.querySelector('foliate-view').dispatchEvent(new MouseEvent('click', " +
                        "{ bubbles: true, composed: true, clientX: innerWidth * 0.85, clientY: innerHeight / 2 })); null",
                    null,
                )
            }
            val tap = session.nextMessage(seconds = 5) { it is ReaderMessage.Tap } as ReaderMessage.Tap?
            assertNotNull("No tap from outside the frames; console: ${session.console}", tap)
            assertTrue("x = ${tap?.x}", tap!!.x in 0.8..0.9)
        }
    }

    // Finding 8 too: a touch that only takes away a selection is no tap; the selection goes before the
    // click comes, so the page has to remember it from the touch's start.
    @Test
    fun `dado uma palavra selecionada quando toco na direita para tirar a seleção então nenhum toque de página chega`() {
        withBook(EpubFixtures.short()) { session ->
            touchScreen(session.webView.screenPointOf("p"), holdMs = 1_500)
            SystemClock.sleep(1_000)
            val selected = evaluateIn(session, "String(!document.querySelector('foliate-view').renderer.getContents()[0].doc.getSelection().isCollapsed)")
            assertTrue("Nothing was selected by the long touch", selected == "\"true\"")
            session.messages.clear()
            touchScreen(session.pointAt(0.85f))
            assertNull(session.nextMessage(seconds = 3) { it is ReaderMessage.Tap })
        }
    }

    private fun evaluateIn(session: Session, script: String): String {
        val answer = java.util.concurrent.CompletableFuture<String>()
        instrumentation.runOnMainSync { session.webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }
}
