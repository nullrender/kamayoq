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
import app.kamayoq.core.reader.ReaderLink
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Piece 12: links always come to the app, and a section with meta refresh stays readable.
@RunWith(AndroidJUnit4::class)
class ReaderLinksTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Session(
        val webView: WebView,
        val client: ReaderClient,
        val messages: LinkedBlockingQueue<ReaderMessage>,
        val console: MutableList<String>,
    )

    private fun <T> withBook(epub: ByteArray, read: (Session) -> T): T {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "links.epub").apply { writeBytes(epub) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        return EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var session: Session
                scenario.onActivity { activity ->
                    val client = ReaderClient(activity.assets, ReaderBook(archive, token = "links1", position = null))
                    val webView = WebView(activity)
                    val console = CopyOnWriteArrayList<String>()
                    activity.setContentView(webView)
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            console += "${message.messageLevel()}: ${message.message()}"
                            return true
                        }
                    }
                    webView.loadReaderPage(client, messages::add)
                    session = Session(webView, client, messages, console)
                }
                try {
                    read(session)
                } finally {
                    scenario.onActivity { session.webView.destroy() }
                }
            }
        }
    }

    private fun Session.evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun Session.nextMessage(seconds: Long = 60, predicate: (ReaderMessage) -> Boolean): ReaderMessage? {
        val deadline = SystemClock.uptimeMillis() + seconds * 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            val message = messages.poll(1, TimeUnit.SECONDS) ?: continue
            if (predicate(message)) return message
        }
        return null
    }

    private fun Session.visibleText() = evaluate(
        "document.querySelector('foliate-view').renderer.getContents().map(c => c.doc.body.textContent).join(' ')",
    )

    /**
     * Touches the link until its message arrives, at most 3 times. A touch right after opening is
     * sometimes lost on the emulator (both links, one in each run): to check on a real phone.
     */
    private fun Session.touchLink(id: String): ReaderMessage? {
        repeat(3) {
            touchScreen(webView.screenPointOf("#$id"))
            nextMessage(seconds = 3) { it is ReaderMessage.Link }?.let { return it }
        }
        return null
    }

    @Test
    fun `dado meta refresh numa seção quando chego nela então o texto continua legível e relocate dispara`() {
        val section = EpubFixtures.HOSTILE_SCRIPTS.size + EpubFixtures.HOSTILE_REQUESTS.indexOf("meta-refresh")
        withBook(EpubFixtures.hostile()) { session ->
            session.nextMessage { it is ReaderMessage.Relocated } ?: error("No position arrived")

            // Sent again while the paginator is busy and drops it, as in ReaderBookTest.
            var arrived: ReaderMessage? = null
            for (attempt in 1..6) {
                session.evaluate("document.querySelector('foliate-view').goTo($section); null")
                arrived = session.nextMessage(seconds = 5) { it is ReaderMessage.Relocated && it.section == section }
                if (arrived != null) break
            }

            assertTrue("No position in the meta refresh section; console: ${session.console}", arrived != null)
            Thread.sleep(2_000)
            val text = session.visibleText()
            assertTrue(text, "Texto comum do caso meta-refresh" in text)
        }
    }

    @Test
    fun `dado um link externo quando toco então o endereço real chega ao Kotlin e nada navega`() {
        withBook(EpubFixtures.links()) { session ->
            session.nextMessage { it is ReaderMessage.Relocated } ?: error("No position arrived")

            val link = session.touchLink("out")

            assertEquals("console: ${session.console}", ReaderMessage.Link("https://exemplo.invalid/real"), link)
            assertEquals(ReaderLink.AskFirst("https://exemplo.invalid/real"), ReaderLink.classify((link as ReaderMessage.Link).href))
            Thread.sleep(1_000)
            assertEquals(emptyList<String>(), session.client.refusedNavigations)
            assertEquals("true", session.evaluate("document.querySelector('foliate-view') !== null"))
        }
    }

    @Test
    fun `dado um link javascript quando toco então nada abre e não há navegação`() {
        withBook(EpubFixtures.links()) { session ->
            session.nextMessage { it is ReaderMessage.Relocated } ?: error("No position arrived")

            val link = session.touchLink("trap")

            assertEquals("console: ${session.console}", ReaderMessage.Link("javascript:alert(1)"), link)
            assertEquals(ReaderLink.Refused, ReaderLink.classify((link as ReaderMessage.Link).href))
            Thread.sleep(1_000)
            assertEquals(emptyList<String>(), session.client.refusedNavigations)
            assertEquals("true", session.evaluate("document.querySelector('foliate-view') !== null"))
        }
    }
}
