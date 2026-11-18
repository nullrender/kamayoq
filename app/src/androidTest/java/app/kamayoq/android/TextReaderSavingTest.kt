package app.kamayoq.android

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, piece 21: the reader's own screen saves each page the person reaches and opens a book at
// the saved place. Piece 14 measured the CFI with a bare WebView; this goes through TextReader.
@RunWith(AndroidJUnit4::class)
class TextReaderSavingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val saves = LinkedBlockingQueue<TextPosition>()

    @Before
    fun openActivity() {
        file = File(instrumentation.targetContext.cacheDir, "saving.epub").apply { writeBytes(EpubFixtures.positions()) }
        archive = EpubArchive.open(file)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun closeActivity() {
        scenario.close()
        archive.close()
        file.delete()
    }

    /** Shows the book from [saved] and waits for the restore; [whileOpening] runs until it comes. */
    private fun show(saved: TextPosition?, whileOpening: () -> Unit = {}) {
        messages.clear()
        scenario.onActivity { activity ->
            activity.setContent {
                TextReader(
                    archive = archive,
                    title = "Livro",
                    reading = TextReading(saved, "emulator"),
                    onSave = saves::add,
                    onBack = {},
                    onMessage = messages::add,
                )
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            whileOpening()
            when (val message = messages.poll(100, TimeUnit.MILLISECONDS)) {
                ReaderMessage.Restored -> return
                is ReaderMessage.Failed -> throw AssertionError("Reader said: $message")
                else -> Unit
            }
        }
        throw AssertionError("Reader did not restore")
    }

    private fun hide() = scenario.onActivity { it.setContentView(View(it)) }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        scenario.onActivity { checkNotNull(it.window.decorView.findWebView()).evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    /** Asks for a move the way the controls do and returns the save it brings. */
    private fun moveAndAwaitSave(script: String): TextPosition {
        saves.clear()
        evaluate("$script; null")
        return saves.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("No save after $script")
    }

    private fun visibleText(): String =
        JSONArray("[" + evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''") + "]").getString(0)

    private fun firstWord(): String = WORD.find(visibleText())?.value ?: throw AssertionError("No word on screen: ${visibleText().take(200)}")

    /** Reads to a place the way a person would (a jump from the contents, then two pages). */
    private fun readToAPlace(): Pair<TextPosition, String> {
        show(saved = null)
        moveAndAwaitSave("document.querySelector('foliate-view').goTo('OEBPS/s3.xhtml#parte-3-1')")
        moveAndAwaitSave("kamayoqReader.next()")
        val saved = moveAndAwaitSave("kamayoqReader.next()")
        return saved to firstWord()
    }

    /** Waits for the reader to settle and shows [word], or fails saying what is there. */
    private fun assertShows(word: String) {
        val whole = Regex("(?<![\\w])$word(?![\\w])")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (whole.containsMatchIn(visibleText())) return
            Thread.sleep(500)
        }
        throw AssertionError("\"$word\" is not on screen; the screen starts with: ${visibleText().take(200)}")
    }

    @Test
    fun `dado um lugar gravado quando fecho e reabro o livro então as mesmas palavras estão visíveis`() {
        val (saved, word) = readToAPlace()
        hide()

        show(saved)

        assertShows(word)
    }

    @Test
    fun `dado um lugar gravado quando giro a tela e o livro reabre então as mesmas palavras estão visíveis`() {
        val (saved, word) = readToAPlace()
        hide()

        // Turning the phone remakes the screen; the app then opens the book again from what it saved.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        instrumentation.waitForIdleSync()
        show(saved)

        assertShows(word)
    }

    @Test
    fun `dado um lugar gravado quando toco na tela e em Próxima durante a abertura então o livro não volta à primeira página`() {
        val (saved, word) = readToAPlace()
        hide()
        saves.clear()

        // Where Next page sits (the row of controls right under the book, on the right) and the
        // middle of the book, the way an impatient finger would, until the book says it has opened.
        // Placed from the book's own view: below the app's window the touch would reach the system.
        show(saved) {
            var book: IntArray? = null
            scenario.onActivity { activity ->
                activity.window.decorView.findWebView()?.let { view ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val controlsRow = (24 * view.resources.displayMetrics.density).toInt()
                    book = intArrayOf(location[0], location[1], view.width, view.height, controlsRow)
                }
            }
            book?.let { (left, top, width, height, controlsRow) ->
                touchScreen(left + width * 0.75f to (top + height + controlsRow).toFloat())
                touchScreen(left + width * 0.5f to top + height * 0.5f)
            }
        }

        assertShows(word)
        val early = saves.toList()
        assertTrue("A place before the restore was kept: $early", early.none { it.section < saved.section })
    }

    private companion object {
        val WORD = Regex("(?<![\\w])m\\d+n\\d+(?![\\w])")
    }
}
