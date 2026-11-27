package app.kamayoq.android

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.ReadingFont
import app.kamayoq.core.reader.TextReading
import app.kamayoq.core.reader.TextSettings
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The S24 defect of 22/11/2026 (TextOwnFontTest) through the app's own path: the book reopened from
// the place kept, as the app reopens it, and the font chosen in the settings panel.
@RunWith(AndroidJUnit4::class)
class TextReaderOwnFontTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var archive: EpubArchive? = null
    private var file: File? = null
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val saves = LinkedBlockingQueue<TextPosition>()
    private var settings by mutableStateOf(TextSettings.DEFAULT)

    @After
    fun close() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive?.close()
        file?.delete()
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        compose.runOnUiThread { checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS).trim('"')
    }

    private fun show(opened: EpubArchive, saved: TextPosition?) = compose.runOnUiThread {
        compose.activity.setContent {
            TextReader(
                opened, "Livro", TextReading(saved, "emulator"),
                onSave = { saves += it.position as TextPosition }, onBack = {}, onMessage = messages::add,
                settings = settings, onSettings = { settings = it },
            )
        }
    }

    private fun awaitRestored() {
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

    private fun moveAndAwaitSave(script: String): TextPosition {
        saves.clear()
        evaluate("$script; null")
        return saves.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("No save after $script")
    }

    private fun top() = evaluate("document.querySelector('foliate-view').lastLocation.range.toString().slice(0, 60)")

    @Test
    fun `dado um livro com letra própria reaberto no meio do segundo capítulo quando escolho uma letra no painel então o trecho do começo da página continua na tela`() {
        val context = compose.activity
        val font = context.assets.open("reader/fonts/IBMPlexSerif-Regular.ttf").use { it.readBytes() }
        val book = File(context.cacheDir, "own-font-app.epub").apply { writeBytes(EpubFixtures.ownFont(font)) }.also { file = it }
        val opened = EpubArchive.open(book).also { archive = it }
        show(opened, saved = null)
        awaitRestored()
        moveAndAwaitSave("document.querySelector('foliate-view').goTo('OEBPS/s2.xhtml')")
        repeat(3) { moveAndAwaitSave("kamayoqReader.next()") }
        val place = moveAndAwaitSave("kamayoqReader.next()")

        // Closed and opened again from the place kept, as the app does; a new screen, not a recomposition.
        compose.runOnUiThread { compose.activity.setContentView(View(compose.activity)) }
        messages.clear()
        show(opened, saved = place)
        awaitRestored()
        Thread.sleep(2_000)
        evaluate("globalThis.anchor = document.querySelector('foliate-view').lastLocation.range.cloneRange(); null")
        val before = top()

        compose.onNodeWithContentDescription(context.getString(R.string.reader_settings_description)).performClick()
        compose.onNodeWithContentDescription("EB Garamond").performClick()
        compose.waitForIdle()
        Thread.sleep(4_000)

        val kept = evaluate(
            "(() => { const range = document.querySelector('foliate-view').lastLocation.range; " +
                "return String(range.comparePoint(anchor.startContainer, anchor.startOffset) === 0) })()",
        )
        val after = top()
        Log.i("KamayoqMeasure", "App path, book with its own font reopened at ${place.fraction.value}, EB Garamond chosen in the panel: start kept $kept; before \"$before\", now \"$after\"; font chosen ${settings.general.font}")
        assertEquals(ReadingFont.GARAMOND, settings.general.font)
        assertEquals("before \"$before\", now \"$after\"", "true", kept)
    }
}
