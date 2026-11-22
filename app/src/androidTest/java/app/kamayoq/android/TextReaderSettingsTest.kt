package app.kamayoq.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.core.reader.TextSettings
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: the settings panel opened from Aa changes the page while the book stays open.
@RunWith(AndroidJUnit4::class)
class TextReaderSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private var saved by mutableStateOf(TextSettings.DEFAULT)

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "settings.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
        compose.setContent {
            TextReader(
                archive, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add,
                settings = saved, onSettings = { saved = it },
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

    private fun fontSize() = evaluate(
        "(() => { const doc = document.querySelector('foliate-view').renderer.getContents()[0].doc; " +
            "return doc.defaultView.getComputedStyle(doc.querySelector('p')).fontSize })()",
    )

    @Test
    fun `dado o livro aberto quando abro Ajustes e aumento a letra então a página muda na hora e o ajuste é guardado`() {
        evaluate("globalThis.samePage = 'yes'")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_settings_description)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_size_up)).performClick()
        compose.waitForIdle()

        assertEquals(19, saved.general.size)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && fontSize() != "\"19px\"") Thread.sleep(250)
        assertEquals("\"19px\"", fontSize())
        assertEquals("\"yes\"", evaluate("globalThis.samePage"))
    }
}
