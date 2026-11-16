package app.kamayoq.android

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextReaderNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var archive: EpubArchive
    private lateinit var file: File
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @Before
    fun openBook() {
        file = File(compose.activity.cacheDir, "navigation.epub").apply { writeBytes(EpubFixtures.short()) }
        archive = EpubArchive.open(file)
        compose.setContent { TextReader(archive, "Livro", {}, messages::add) }
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
        // Release the screen before the archive: a WebView may still be reading an entry.
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
        compose.runOnUiThread {
            checkNotNull(compose.activity.window.decorView.findWebView()).evaluateJavascript(script, answer::complete)
        }
        return answer.get(30, TimeUnit.SECONDS)
    }

    private fun button(label: Int) = compose.onNodeWithText(compose.activity.getString(label))

    private fun navigation(): ReaderMessage.Relocated {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            when (val message = messages.poll(1, TimeUnit.SECONDS)) {
                is ReaderMessage.Relocated -> if (message.reason.byPerson) return message
                is ReaderMessage.Failed -> throw AssertionError("Reader said: $message")
                else -> Unit
            }
        }
        throw AssertionError("No position after navigation")
    }

    @Test
    fun `dado o livro aberto quando toco próxima página e anterior então os botões viram a página`() {
        val start = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        messages.clear()
        button(R.string.reader_next_page).performClick()
        val next = navigation()
        assertNotEquals(start, "\"${next.cfi}\"")
        button(R.string.reader_previous_page).performClick()
        val previous = navigation()
        assertNotEquals(next.cfi, previous.cfi)
    }

    @Test
    fun `dado o sumário aberto quando pressiono Voltar então ele fecha sem mudar o trecho ou reabrir a página`() {
        val start = evaluate("document.querySelector('foliate-view').lastLocation.cfi")
        evaluate("globalThis.screenIdentity = 'same-page'")
        button(R.string.reader_contents).performClick()
        compose.onNodeWithText("Seção 2").assertExists()
        val reader = compose.activity
        // A real key goes to the window in front, as the phone's Back does.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // Back must reach the sheet's own window; reaching the screen below closes the whole reader,
        // and then the sheet is gone for the wrong reason. The system closes the reader, or sends it
        // back, after the call returns, so the reader is watched for a while instead of checked once.
        val watchUntil = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < watchUntil) {
            val inFront = reader.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            assertFalse("Back closed or hid the reader", reader.isFinishing || reader.isDestroyed || !inFront)
            Thread.sleep(100)
        }
        compose.onNodeWithText("Seção 2").assertDoesNotExist()
        assertEquals(start, evaluate("document.querySelector('foliate-view').lastLocation.cfi"))
        assertEquals("\"same-page\"", evaluate("globalThis.screenIdentity"))
    }

    @Test
    fun `dado o sumário quando escolho a segunda seção então o livro navega para ela sem reiniciar`() {
        evaluate("globalThis.screenIdentity = 'same-page'")
        messages.clear()
        button(R.string.reader_contents).performClick()
        compose.onNodeWithText("Seção 2").performClick()
        assertEquals(1, navigation().section)
        assertEquals("\"same-page\"", evaluate("globalThis.screenIdentity"))
        assertTrue(evaluate("document.querySelector('foliate-view').renderer.getContents().some(c => c.index === 1)").toBoolean())
    }

    // The checks run before each action, on the whole window: the reader with its controls, then
    // the contents sheet. A failure names the view and the rule it breaks.
    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun `dado o leitor aberto quando rodo as verificações de acessibilidade do Compose então nenhum controle falha`() {
        compose.enableAccessibilityChecks()
        messages.clear()
        button(R.string.reader_contents).performClick()
        compose.onNodeWithText("Seção 2").performClick()
        assertEquals(1, navigation().section)
    }
}
