package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 4, piece 24: a place saved by the app's own store survives the process being killed, and an
 * update installed over the app. A test inside the process cannot kill itself, so the emulator
 * workflow runs the save, kills the app with `am force-stop`, runs the check, installs the app
 * over itself and runs the check again (`.github/scripts/process-death.sh`). Each runs only when the
 * workflow names it in the `processDeath` argument, so the ordinary run skips both.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as KamayoqApp
    private val expected = File(app.filesDir, "process-death.json")
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    private fun only(step: String) =
        assumeTrue("Run by the emulator workflow only", InstrumentationRegistry.getArguments().getString("processDeath") == step)

    /** Two books of the positions fixture, kept in the app's own files so the second run finds them. */
    private val books = listOf("Books/Livro A.epub", "Books/Livro B.epub")

    private fun bookFile(path: String) = File(app.filesDir, path.substringAfterLast('/'))

    private fun chapterOf(path: String): String = app.store.works()
        .flatMap { app.store.chapters(it.id) }
        .single { it.path == path }.id

    /** Shows [path] from what the store holds for it, saving through the app's own save. */
    private fun ActivityScenario<ComponentActivity>.show(path: String, archive: EpubArchive) {
        val chapterId = chapterOf(path)
        val round = runBlocking { app.opening.turns.open { it } }
        messages.clear()
        onActivity { activity ->
            activity.setContent {
                TextReader(
                    archive = archive,
                    title = path,
                    reading = TextReading(app.store.progress(chapterId), "emulator"),
                    onSave = textSaving(app.store, app.opening.turns, round, chapterId),
                    onBack = {},
                    onMessage = messages::add,
                )
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                ReaderMessage.Restored -> return
                is ReaderMessage.Failed -> throw AssertionError("Reader said: $message")
                else -> Unit
            }
        }
        throw AssertionError("Reader did not restore $path")
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun ActivityScenario<ComponentActivity>.evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        onActivity { checkNotNull(it.window.decorView.findWebView()).evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun ActivityScenario<ComponentActivity>.visibleText(): String =
        JSONArray("[" + evaluate("document.querySelector('foliate-view').lastLocation?.range?.toString() ?? ''") + "]").getString(0)

    /** Moves the way a person would and waits for the store to hold the move. */
    private fun ActivityScenario<ComponentActivity>.move(path: String, script: String) {
        val chapterId = chapterOf(path)
        val before = app.store.progress(chapterId).position
        evaluate("$script; null")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (app.store.progress(chapterId).position != before) return
            Thread.sleep(200)
        }
        throw AssertionError("The store did not take the move $script in $path")
    }

    @Test
    fun `dado dois livros pela metade quando a rodada lê até um ponto em cada então o banco do app guarda os dois`() {
        only("save")
        val stamps = books.associateWith { path ->
            bookFile(path).writeBytes(EpubFixtures.positions())
            FileStamp(bookFile(path).length(), 1)
        }
        app.store.importLocal("content://test/tree/primary%3AKamayoq", FolderConvention.read(books), stamps)
        val words = JSONObject()
        // Two books left halfway, at different places: both must stay.
        val places = mapOf(
            books[0] to listOf("document.querySelector('foliate-view').goTo('OEBPS/s3.xhtml#parte-3-1')", "kamayoqReader.next()", "kamayoqReader.next()"),
            books[1] to listOf("document.querySelector('foliate-view').goTo('OEBPS/s2.xhtml#parte-2-2')", "kamayoqReader.next()"),
        )
        for ((path, moves) in places) {
            EpubArchive.open(bookFile(path)).use { archive ->
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.show(path, archive)
                    moves.forEach { scenario.move(path, it) }
                    words.put(path, WORD.find(scenario.visibleText())?.value ?: throw AssertionError("No word on screen in $path"))
                }
            }
        }
        expected.writeText(words.toString())
        // The workflow kills the process next. Each move was already in the store before this run
        // ends: this proves the kept places survive, not a save still under way at the kill.
    }

    @Test
    fun `dado o processo morto quando o app volta então os dois livros reabrem nas mesmas palavras`() {
        only("check")
        val words = JSONObject(expected.readText())
        for (path in books) {
            assertNotNull("No place kept for $path", app.store.progress(chapterOf(path)).position as? TextPosition)
            val word = words.getString(path)
            EpubArchive.open(bookFile(path)).use { archive ->
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.show(path, archive)
                    val whole = Regex("(?<![\\w])$word(?![\\w])")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (!whole.containsMatchIn(scenario.visibleText())) {
                        if (System.nanoTime() > deadline) {
                            throw AssertionError("\"$word\" is not on screen in $path; it starts with: ${scenario.visibleText().take(200)}")
                        }
                        Thread.sleep(500)
                    }
                }
            }
        }
    }

    private companion object {
        val WORD = Regex("(?<![\\w])m\\d+n\\d+(?![\\w])")
    }
}
