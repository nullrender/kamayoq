package app.kamayoq.android

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.kavita.KavitaPlace
import app.kamayoq.core.kavita.KavitaText
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, milestone C2: a place from Kavita opens by its section and marker, and every page the person
// reaches names its first element in Kavita's form, taken on the section as the WebView renders it.
// Each word of the fixtures is unique, so the words on screen say where the book is: in the positions
// book, paragraph n of section s starts with the word m<s+1>n<(n-1)*40+1>.
@RunWith(AndroidJUnit4::class)
class TextReaderKavitaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var archive: EpubArchive? = null
    private var file: File? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @After
    fun close() {
        scenario?.close()
        archive?.close()
        file?.delete()
    }

    private fun open(book: ByteArray) {
        val written = File(instrumentation.targetContext.cacheDir, "kavita.epub").apply { writeBytes(book) }
        file = written
        archive = EpubArchive.open(written)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    /** A place as a sync with Kavita leaves it: its section, the count Kavita has, and the marker. */
    private fun fromKavita(page: Int, sections: Int, marker: String?): TextReading {
        val remote = checkNotNull(KavitaText.fromRemote(KavitaPlace(page, marker), sections, Instant.EPOCH))
        return TextReading(ChapterProgress(null, remote.position, 0), "emulator", savedMarker = marker)
    }

    private fun show(reading: TextReading) {
        messages.clear()
        checkNotNull(scenario).onActivity { activity ->
            activity.setContent {
                TextReader(checkNotNull(archive), "Livro", reading, onSave = {}, onBack = {}, onMessage = { messages += it })
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(100, TimeUnit.MILLISECONDS)) {
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

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        checkNotNull(scenario).onActivity { checkNotNull(it.window.decorView.findWebView()).evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun visibleText(): String =
        JSONArray("[" + evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''") + "]").getString(0)

    /** The first word on screen and its number in the section, from `m<seed>n<number>`. */
    private fun firstWord(): Pair<String, Int> {
        val word = WORD.find(visibleText())?.value ?: throw AssertionError("No word on screen: ${visibleText().take(200)}")
        return word to word.substringAfter('n').toInt()
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

    /** Moves the way the controls do and returns the place the page reported for the person's move. */
    private fun move(script: String): ReaderMessage.Relocated {
        messages.clear()
        evaluate("$script; null")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val message = messages.poll(100, TimeUnit.MILLISECONDS)
            if (message is ReaderMessage.Relocated && message.reason.byPerson) return message
        }
        throw AssertionError("No move after $script")
    }

    @Test
    fun `dado o marcador de um parágrafo no meio de uma seção quando abro o livro com ele então o parágrafo está na tela`() {
        open(EpubFixtures.positions())

        show(fromKavita(page = 1, sections = 3, marker = "//body/p[15]"))

        assertShows("m2n561")
    }

    @Test
    fun `dado idx 12 como marcador quando abro então a seção abre no começo`() {
        open(EpubFixtures.positions())

        show(fromKavita(page = 1, sections = 3, marker = "idx:12"))

        assertShows("m2n1")
    }

    @Test
    fun `dado o Kavita contando outro número de seções quando abro então o livro abre no começo`() {
        open(EpubFixtures.positions())

        show(fromKavita(page = 1, sections = 4, marker = "//body/p[15]"))

        assertShows("m1n1")
    }

    // A section above the paginator's threshold renders in chunks; each keeps the section's elements.
    @Test
    fun `dado o marcador de um parágrafo num pedaço adiante de uma seção grande quando abro então o parágrafo está na tela`() {
        open(EpubFixtures.positionsCompiled())

        show(fromKavita(page = 0, sections = 1, marker = "//body/p[1500]"))

        assertShows("m1n${1499 * 40 + 1}")
    }

    @Test
    fun `dado uma página virada pela pessoa então o marcador da mensagem é o parágrafo da primeira palavra na tela`() {
        open(EpubFixtures.positions())
        show(TextReading(null as TextPosition?, "emulator"))
        move("document.querySelector('foliate-view').goTo('OEBPS/s3.xhtml#parte-3-1')")
        move("kamayoqReader.next()")

        val place = move("kamayoqReader.next()")

        val (word, number) = firstWord()
        assertEquals("first word $word", "//body/p[${(number - 1) / 40 + 1}]", place.marker)
    }

    // The measure of milestone C3: what a round trip through Kavita's marker costs, on the page the
    // WebView renders. The server keeps the marker byte for byte (KavitaEpubFactsTest), so the loss is
    // all here. It enters green: the number is what the WebView and foliate-js give, and the checks
    // hold only what the conversion promises, the same section and never a later place. The emulator
    // workflow prints the KamayoqMeasure lines after the tests.
    @Test
    fun `dado um lugar no meio de uma seção quando vai ao Kavita pelo marcador e volta então a seção é a mesma e a perda fica medida`() {
        open(EpubFixtures.positions())
        val losses = mutableListOf<Int>()
        for (pages in 1..4) {
            show(TextReading(null as TextPosition?, "emulator"))
            move("document.querySelector('foliate-view').goTo('OEBPS/s2.xhtml#parte-2-1')")
            var place = move("kamayoqReader.next()")
            repeat(pages - 1) { place = move("kamayoqReader.next()") }
            val (before, numberBefore) = firstWord()
            val marker = checkNotNull(place.marker) { "No marker at $before" }

            show(fromKavita(page = place.section, sections = place.sections, marker = marker))

            // The paragraph the marker names starts on the page it reopens at.
            PARAGRAPH.matchEntire(marker)?.let { assertShows("m2n${(it.groupValues[1].toInt() - 1) * 40 + 1}") }
            val (after, numberAfter) = firstWord()
            assertEquals("section of $after", before.substringBefore('n'), after.substringBefore('n'))
            assertTrue("$after came after $before", numberAfter <= numberBefore)
            losses += numberBefore - numberAfter
            Log.i(MEASURE, "Kavita round trip: $before ($marker) came back at $after, ${numberBefore - numberAfter} words back")
        }
        Log.i(MEASURE, "Kavita round trip, words back per place: $losses")
    }

    private companion object {
        val WORD = Regex("m\\d+n\\d+")
        val PARAGRAPH = Regex("//body/p\\[(\\d+)]")
        const val MEASURE = "KamayoqMeasure"
    }
}
