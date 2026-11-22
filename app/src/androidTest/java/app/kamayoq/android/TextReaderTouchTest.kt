package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: the sides of the page turn it and the middle shows or hides the controls (board
// SinteseLeitorPaginado); the controls are there when the book opens.
@RunWith(AndroidJUnit4::class)
class TextReaderTouchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "touch.epub").apply { writeBytes(EpubFixtures.short()) }
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

    private fun webView() = checkNotNull(compose.activity.window.decorView.findWebView())

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread { webView().evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    /** A point on the book at [across] of its width, halfway down. */
    private fun pointAt(across: Float): Pair<Float, Float> {
        val location = IntArray(2)
        var size = 0 to 0
        compose.runOnUiThread {
            val view = webView()
            view.getLocationOnScreen(location)
            size = view.width to view.height
        }
        return location[0] + size.first * across to location[1] + size.second / 2f
    }

    private fun controlsShown() =
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.reader_back_to_work)).fetchSemanticsNodes().isNotEmpty()

    /** Touches until [done] holds, at most 3 times: a touch right after opening is sometimes lost on the emulator. */
    private fun touchUntil(across: Float, done: () -> Boolean): Boolean {
        repeat(3) {
            touchScreen(pointAt(across))
            compose.waitForIdle()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                if (done()) return true
                Thread.sleep(100)
            }
        }
        return false
    }

    @Test
    fun `dado o livro aberto então os controles aparecem`() {
        compose.waitForIdle()
        assertTrue(controlsShown())
    }

    @Test
    fun `dado um toque na direita da página então a página avança e os controles recuam`() {
        val start = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        assertTrue("The page did not turn", touchUntil(0.85f) { evaluate("document.querySelector('foliate-view').lastLocation.cfi") != start })
        compose.waitForIdle()
        assertTrue("The controls stayed", !controlsShown())
    }

    @Test
    fun `dado a página avançada quando toco na esquerda então ela volta`() {
        val start = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        assertTrue(touchUntil(0.85f) { evaluate("document.querySelector('foliate-view').lastLocation.cfi") != start })
        val ahead = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        assertNotEquals(start, ahead)
        assertTrue("The page did not go back", touchUntil(0.15f) { evaluate("document.querySelector('foliate-view').lastLocation.cfi") == start })
    }

    @Test
    fun `dado os controles à vista quando toco no meio então eles somem e com outro toque voltam`() {
        assertTrue("The controls did not hide", touchUntil(0.5f) { !controlsShown() })
        assertTrue("The controls did not come back", touchUntil(0.5f) { controlsShown() })
    }

    @Test
    fun `dado um toque no meio então a página não vira`() {
        val start = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        assertTrue(touchUntil(0.5f) { !controlsShown() })
        Thread.sleep(1_000)
        assertTrue(evaluate("document.querySelector('foliate-view').lastLocation.cfi") == start)
    }
}
