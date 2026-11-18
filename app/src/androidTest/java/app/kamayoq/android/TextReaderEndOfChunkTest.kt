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
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Piece 23, found by the Codex review of 18/11: the fork cuts a section above 512 KiB into chunks and
// still reports the whole section, so the end of a chunk that is not the last must not end the book.
@RunWith(AndroidJUnit4::class)
class TextReaderEndOfChunkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "chunks.epub").apply { writeBytes(EpubFixtures.positionsCompiled()) }
        archive = EpubArchive.open(file)
        compose.setContent {
            TextReader(archive, "Novela", TextReading(saved = null as TextPosition?, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add)
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

    @After
    fun closeBook() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive.close()
        file.delete()
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread { checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun visibleText(): String =
        JSONArray("[" + evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''") + "]").getString(0)

    private fun endBlockShows() = compose.onAllNodesWithText(END_OF_BOOK).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `dado o fim do primeiro pedaço de uma seção grande quando toco próxima então o livro segue sem o bloco de fim`() {
        // The last paragraph the first chunk really holds: later ones are empty placeholders.
        val lastWord = JSONArray(
            "[" + evaluate(
                "(() => { const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc; " +
                    "const real = [...doc.querySelectorAll('p')].filter(p => !p.hasAttribute('$CHUNK') && p.textContent.trim()); " +
                    "return real.at(-1).textContent.trim().split(/\\s+/).at(-1) })()",
            ) + "]",
        ).getString(0)
        val placeholders = evaluate(
            "document.querySelector('foliate-view').renderer.getContents()[0].doc.querySelectorAll('[$CHUNK]').length",
        ).toInt()
        assertTrue("The book is not cut into chunks, so this test proves nothing", placeholders > 0)
        evaluate(
            "document.querySelector('foliate-view').renderer.goTo({ index: 0, anchor: doc => " +
                "[...doc.querySelectorAll('p')].filter(p => !p.hasAttribute('$CHUNK') && p.textContent.trim()).at(-1) }); null",
        )
        Thread.sleep(2_000)

        repeat(4) {
            compose.onNodeWithText(compose.activity.getString(R.string.reader_next_page)).performClick()
            compose.waitForIdle()
            Thread.sleep(700)
        }

        assertFalse("The end block showed at the end of a chunk ($lastWord)", endBlockShows())
        val number = Regex("m1n(\\d+)").findAll(visibleText()).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        assertTrue(
            "The reader did not move past the chunk's last word $lastWord; on screen: ${visibleText().take(120)}",
            number > lastWord.removePrefix("m1n").toInt(),
        )
    }

    private companion object {
        const val END_OF_BOOK = "End of the book"
        const val CHUNK = "data-foliate-chunk"
    }
}
