package app.kamayoq.android

import android.content.pm.ActivityInfo
import android.os.SystemClock
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
import java.util.concurrent.CopyOnWriteArrayList
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

    // When the book said it opened and when each place was handed out, on the uptime clock.
    @Volatile private var restoredAt: Long? = null
    private val saveTimes = CopyOnWriteArrayList<Long>()

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
        restoredAt = null
        scenario.onActivity { activity ->
            activity.setContent {
                TextReader(
                    archive = archive,
                    title = "Livro",
                    reading = TextReading(saved, "emulator"),
                    onSave = { progress ->
                        saveTimes += SystemClock.uptimeMillis()
                        saves += progress.position as TextPosition
                    },
                    onBack = {},
                    onMessage = { message ->
                        if (message == ReaderMessage.Restored) restoredAt = SystemClock.uptimeMillis()
                        messages += message
                    },
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

    /**
     * The middle of Next page and of the book, from the book's own view, or null before it exists.
     * Under the book sit Contents, in a row of its own, and then Previous and Next, each row at least
     * 48 dp (TextReaderControls); below the app's window a touch would reach the system instead.
     */
    private fun targets(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        var found: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null
        scenario.onActivity { activity ->
            activity.window.decorView.findWebView()?.let { view ->
                val (left, top) = IntArray(2).also(view::getLocationOnScreen)
                val dp = view.resources.displayMetrics.density
                val next = left + view.width * 0.75f to top + view.height + (48 + 24) * dp
                val middle = left + view.width * 0.5f to top + view.height * 0.5f
                found = next to middle
            }
        }
        return found
    }

    @Test
    fun `dado um lugar gravado quando toco na tela e em Próxima durante a abertura então o livro não volta à primeira página`() {
        val (saved, word) = readToAPlace()
        hide()
        saves.clear()
        saveTimes.clear()
        val taps = CopyOnWriteArrayList<Long>()

        // An impatient finger on Next page and on the book until the book says it has opened.
        show(saved) {
            if (restoredAt == null) {
                targets()?.let { (next, middle) ->
                    touchScreen(next)
                    touchScreen(middle)
                    // Taken after the touches: a time before the restore means the touches were too.
                    taps += SystemClock.uptimeMillis()
                }
            }
        }
        val opened = checkNotNull(restoredAt)

        assertTrue("No touch landed before the book opened", taps.any { it < opened })
        assertShows(word)
        assertTrue("A place was handed out before the book opened", saveTimes.none { it < opened })
        // The same spot, now that the controls work, turns the page: it is Next page indeed.
        saves.clear()
        touchScreen(checkNotNull(targets()).first)
        assertTrue("The spot touched is not Next page", saves.poll(15, TimeUnit.SECONDS) != null)
    }

    // A changed edition can drop the spine item the CFI names: the fork then opens the book at its
    // start, and the saved section is the nearest place the person was.
    @Test
    fun `dado um CFI cuja seção não existe mais quando reabro então o livro abre no começo da seção gravada`() {
        val (saved, _) = readToAPlace()
        hide()

        show(saved.copy(cfi = "epubcfi(/6/99!/4/2/1:0)"))

        assertShows(SECTION_3_START)
    }

    // Measured, not built: the fork turns a path it cannot find inside the section into the start of
    // that section (epubcfi.js, toRange returns null; paginator.js, anchor ?? 0).
    @Test
    fun `dado um CFI cujo trecho sumiu da seção quando reabro então o livro abre no começo da seção gravada`() {
        val (saved, _) = readToAPlace()
        hide()

        show(saved.copy(cfi = saved.cfi.substringBefore('!') + "!/4/999/1:0)"))

        assertShows(SECTION_3_START)
    }

    private companion object {
        val WORD = Regex("(?<![\\w])m\\d+n\\d+(?![\\w])")

        /** The first word of the third section of the positions book. */
        const val SECTION_3_START = "m3n1"
    }
}
