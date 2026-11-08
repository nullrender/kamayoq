package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

// Piece 17: the screen the app shows for a book, with the same page and defenses as pieces 6 to 14.
@RunWith(AndroidJUnit4::class)
class TextReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun firstAnswer(messages: LinkedBlockingQueue<ReaderMessage>): ReaderMessage? {
        while (true) {
            when (val message = messages.poll(60, TimeUnit.SECONDS)) {
                ReaderMessage.Ready, is ReaderMessage.Relocated -> Unit
                else -> return message
            }
        }
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun showing(book: ByteArray, check: (LinkedBlockingQueue<ReaderMessage>) -> Unit) =
        showingOnScreen(book) { messages, _ -> check(messages) }

    private fun showingOnScreen(book: ByteArray, check: (LinkedBlockingQueue<ReaderMessage>, ActivityScenario<ComponentActivity>) -> Unit) {
        val file = File(context.cacheDir, "text-reader.epub").apply { writeBytes(book) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TextReader(archive, title = "Dom Casmurro", onBack = {}, onMessage = messages::add) }
                }
                check(messages, scenario)
            }
        }
    }

    @Test
    fun `dado um capítulo EPUB quando a tela do leitor abre então o livro aparece no início`() = showing(EpubFixtures.short()) { messages ->
        when (val answer = firstAnswer(messages)) {
            ReaderMessage.Restored -> Unit
            null -> fail("The book never showed")
            else -> fail("Reader said: $answer")
        }
    }

    @Test
    fun `dado um livro de layout fixo quando a tela do leitor abre então ela diz que ainda não abre`() = showing(EpubFixtures.fixedLayout()) { messages ->
        assertEquals(ReaderMessage.Failed("fixed-layout"), firstAnswer(messages))
    }

    // A WebView in wrap-content height gives the page a viewport of height 0: the book was laid out
    // in a 96 px strip and the screen stayed white on a Galaxy S24 (08/11/2026).
    @Test
    fun `dado um capítulo EPUB quando a tela do leitor abre então o livro ocupa a altura da tela`() = showingOnScreen(EpubFixtures.short()) { messages, scenario ->
        assertEquals(ReaderMessage.Restored, firstAnswer(messages))
        val height = CompletableFuture<String>()
        scenario.onActivity { activity ->
            val webView = checkNotNull(activity.window.decorView.findWebView()) { "No WebView on screen" }
            webView.evaluateJavascript("document.querySelector('foliate-view').getBoundingClientRect().height", height::complete)
        }
        val pixels = height.get(30, TimeUnit.SECONDS).toDouble()
        assertTrue("The book is $pixels px high", pixels > 300)
    }
}
