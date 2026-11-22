package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextReaderNovelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @Before
    fun writeBook() {
        file = File(compose.activity.cacheDir, "novel.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
    }

    @After
    fun closeBook() {
        // Release the screen before the archive: a WebView may still be reading an entry.
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive.close()
        file.delete()
    }

    private fun open(type: WorkType, end: TextEnd = TextEnd.Book) {
        compose.setContent {
            TextReader(
                archive, "Capítulo", TextReading(null, "emulator"),
                onSave = {}, onBack = {}, onMessage = messages::add, type = type, end = end,
            )
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> return
                is ReaderMessage.Failed -> throw AssertionError("Reader said: $message")
                else -> Unit
            }
        }
        throw AssertionError("Reader did not restore")
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun flow(): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView())
                .evaluateJavascript("document.querySelector('foliate-view').renderer.getAttribute('flow')", answer::complete)
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `dado um capítulo de romance quando ele abre então o texto rola`() {
        open(WorkType.NOVELS)
        assertEquals("\"scrolled\"", flow())
    }

    @Test
    fun `dado um livro quando ele abre então o texto vem em páginas`() {
        open(WorkType.BOOKS)
        assertEquals("\"paginated\"", flow())
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The last section from the contents, then Next page until [block] shows, as the end-of-book test does. */
    private fun readToTheEnd(block: String) {
        compose.onNodeWithText(compose.activity.getString(R.string.reader_chapters)).performClick()
        compose.onNodeWithText("Seção 3").performClick()
        compose.waitForIdle()
        Thread.sleep(1_000)
        repeat(80) {
            if (shows(block)) return
            compose.pageAction(R.string.reader_next_page)
            compose.waitForIdle()
            Thread.sleep(300)
        }
        throw AssertionError("No \"$block\" after 80 screens of the last section")
    }

    @Test
    fun `dado o fim de um capítulo de romance quando toco no próximo então o bloco de fim leva ao capítulo seguinte`() {
        val opened = AtomicInteger()
        open(WorkType.NOVELS, TextEnd.Chapter("Cap. 0929") { opened.incrementAndGet() })

        readToTheEnd(compose.activity.getString(R.string.end_of_chapter))
        compose.onNodeWithText(compose.activity.getString(R.string.next_chapter, "Cap. 0929")).performClick()

        assertEquals(1, opened.get())
    }

    @Test
    fun `dado o fim do último capítulo de um romance então o bloco de fim diz que é o último`() {
        open(WorkType.NOVELS, TextEnd.Chapter(nextName = null) {})

        readToTheEnd(compose.activity.getString(R.string.end_of_chapter))

        compose.onNodeWithText(compose.activity.getString(R.string.no_next_chapter)).assertExists()
    }
}
