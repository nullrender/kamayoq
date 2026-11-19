package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.reader.BookContents
import app.kamayoq.core.reader.ContentsEntry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.EpubContents
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, piece B4: the chapters come from the table of contents read in Kotlin, and the page says
// where the person is by the number the fork gives each item. The two must be the same list. A
// measurement of the fork: it has no honest red, so it went in green.
@RunWith(AndroidJUnit4::class)
class ReaderContentsAgreementTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var archive: EpubArchive? = null
    private var file: File? = null
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @After
    fun closeBook() {
        // Release the screen before the archive: a WebView may still be reading an entry.
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive?.close()
        file?.delete()
    }

    /** Opens [book] in the reader and returns what Kotlin reads of its table of contents. */
    private fun open(book: ByteArray): BookContents {
        val written = File(compose.activity.cacheDir, "agreement.epub").apply { writeBytes(book) }
        file = written
        val opened = EpubArchive.open(written)
        archive = opened
        compose.setContent {
            TextReader(opened, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> return EpubContents.read { name ->
                    if (name in opened.names) opened.open(name).use { it.readBytes().decodeToString() } else null
                }
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
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete)
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    /** evaluateJavascript does not wait for a promise: the script leaves its answer in a global. */
    private fun evaluateAsync(body: String): String {
        evaluate("globalThis.agreement = null; (async () => { $body })().then(value => { globalThis.agreement = JSON.stringify(value) }, error => { globalThis.agreement = JSON.stringify({ error: String(error) }) }); null")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val answer = evaluate("globalThis.agreement")
            if (answer != "null") return unquoted(answer)
            Thread.sleep(200)
        }
        throw AssertionError("The page did not answer")
    }

    /** evaluateJavascript hands a string back as JSON text. */
    private fun unquoted(answer: String): String = JSONTokener(answer).nextValue() as String

    private data class Listed(val id: Int, val label: String, val href: String?)

    /** The fork's table of contents, flattened as its own progress code does, with the number of each item. */
    private fun forkItems(): List<Listed> {
        val raw = evaluate(
            "(() => { const flat = items => (items ?? []).flatMap(item => [{ id: item.id, label: item.label ?? '', href: item.href ?? null }, ...flat(item.subitems)]); " +
                "return JSON.stringify(flat(document.querySelector('foliate-view').book.toc)) })()",
        )
        val items = JSONArray(unquoted(raw))
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            Listed(item.getInt("id"), item.getString("label"), if (item.isNull("href")) null else item.getString("href"))
        }
    }

    private fun kotlinItems(contents: BookContents): List<Listed> {
        val listed = mutableListOf<Listed>()
        fun visit(entries: List<ContentsEntry>) {
            for (entry in entries) {
                listed += Listed(listed.size, entry.label, entry.href)
                visit(entry.subitems)
            }
        }
        visit(contents.entries)
        return listed
    }

    // One book per test: the screen of a test is set once.
    private fun sameList(book: ByteArray) {
        val contents = open(book)
        assertEquals(forkItems(), kotlinItems(contents))
    }

    @Test
    fun `dado o romance em volumes quando o Kotlin e o leitor leem o sumário então a lista é a mesma com os mesmos números`() =
        sameList(EpubFixtures.novel())

    @Test
    fun `dado o romance compilado numa seção só quando o Kotlin e o leitor leem o sumário então a lista é a mesma`() =
        sameList(EpubFixtures.compiled())

    @Test
    fun `dado um EPUB 2 com NCX quando o Kotlin e o leitor leem o sumário então a lista é a mesma`() =
        sameList(EpubFixtures.epub2WithNcx())

    @Test
    fun `dado o livro curto quando o Kotlin e o leitor leem o sumário então a lista é a mesma`() =
        sameList(EpubFixtures.short())

    @Test
    fun `dado o romance em volumes quando o leitor vai a cada capítulo então o item que ele diz é o capítulo do Kotlin`() {
        val contents = open(EpubFixtures.novel())
        assertEquals(
            listOf("Capítulo 1", "Capítulo 2", "Capítulo 3", "Capítulo 4", "Capítulo 5"),
            contents.chapters.map { it.title },
        )

        for ((index, chapter) in contents.chapters.withIndex()) {
            val href = JSONObject.quote(chapter.key)
            val item = evaluateAsync(
                "const view = document.querySelector('foliate-view'); await view.goTo($href); " +
                    "await new Promise(done => setTimeout(done, 500)); return view.lastLocation?.tocItem?.id ?? null",
            )
            assertEquals("Item at ${chapter.key}: $item", index, contents.chapterOfItem[item.toInt()])
        }
    }

    // The page before the first item belongs to the first chapter by the page's own rule, tested in
    // messages.test.mjs; here only what the fork reports there is measured.
    @Test
    fun `dado o romance em volumes quando o livro abre na capa então o fork não diz item nenhum`() {
        open(EpubFixtures.novel())

        assertEquals("null", evaluate("document.querySelector('foliate-view').lastLocation?.tocItem?.id ?? null"))
    }

    @Test
    fun `dado o romance em volumes quando o leitor vai à página de rosto do volume 2 então ela leva ao capítulo 4`() {
        val contents = open(EpubFixtures.novel())

        val item = evaluateAsync(
            "const view = document.querySelector('foliate-view'); await view.goTo('OEBPS/rosto2.xhtml'); " +
                "await new Promise(done => setTimeout(done, 500)); return view.lastLocation?.tocItem?.id ?? null",
        )
        // The title page is a screen of its own: the fork names the volume's item, not a chapter's.
        assertEquals("Volume 2", kotlinItems(contents)[item.toInt()].label)
        assertEquals("Capítulo 4", contents.chapters[contents.chapterOfItem[item.toInt()]].title)
    }
}
