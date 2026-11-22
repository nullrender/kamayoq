package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: Go to and a book's previous and next chapter from the bars.
@RunWith(AndroidJUnit4::class)
class TextReaderMovesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "moves.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
        compose.setContent { TextReader(archive, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add) }
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

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun section() = evaluate("document.querySelector('foliate-view').lastLocation.section.current").toIntOrNull()

    @Test
    fun `dado Ir para quando ponho a metade e toco em Ir então o livro vai para perto da metade`() {
        // The place in the description moves with the page: the start of it is enough.
        val goTo = compose.activity.getString(R.string.reader_go_to_place, "")
        compose.onNode(hasContentDescription(goTo, substring = true)).performClick()
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.reader_go_slider_book)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithText(compose.activity.getString(R.string.reader_go)).performClick()

        assertTrue(
            "The book did not move to the middle",
            waitUntil { evaluate("document.querySelector('foliate-view').lastLocation.fraction").toDoubleOrNull()?.let { it in 0.4..0.65 } == true },
        )
    }

    @Test
    fun `dado um livro na primeira seção quando toco em próximo capítulo e depois em anterior então ele vai à segunda e volta à primeira`() {
        assertEquals(0, section())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_next_chapter)).performClick()
        assertTrue("Next chapter did not reach the second section", waitUntil { section() == 1 })
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_previous_chapter)).performClick()
        assertTrue("Previous chapter did not come back", waitUntil { section() == 0 })
    }
}
