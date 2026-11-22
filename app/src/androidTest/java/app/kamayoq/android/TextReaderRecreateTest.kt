package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Gemini's review of slice 5, finding 6: turning the phone remakes the screen, and the panel open on
// it closed.
@RunWith(AndroidJUnit4::class)
class TextReaderRecreateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var archive: EpubArchive? = null
    private var file: File? = null
    private val messages = LinkedBlockingQueue<ReaderMessage>()

    @After
    fun close() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive?.close()
        file?.delete()
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

    @Test
    fun `dado o painel Ajustes aberto quando a tela é refeita então ele continua aberto`() {
        val book = File(compose.activity.cacheDir, "recreate.epub").apply { writeBytes(EpubFixtures.short()) }.also { file = it }
        val opened = EpubArchive.open(book).also { archive = it }
        val tester = StateRestorationTester(compose)
        tester.setContent {
            TextReader(opened, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add, onSettings = {})
        }
        awaitRestored()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_settings_description)).performClick()
        val title = compose.activity.getString(R.string.reader_panel_title)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertTrue("The panel closed", compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
    }
}
