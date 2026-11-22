package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Gemini's review of slice 5, finding 8: the page-turn actions ran before the book opened, and a turn
// asked of a page with no book yet fails inside it.
@RunWith(AndroidJUnit4::class)
class TextReaderEarlyTest {
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

    @Test
    fun `dado o livro ainda abrindo quando peço a próxima página pela ação de acessibilidade então o livro abre mesmo assim`() {
        val book = File(compose.activity.cacheDir, "early.epub").apply { writeBytes(EpubFixtures.short()) }.also { file = it }
        val opened = EpubArchive.open(book).also { archive = it }
        compose.setContent { TextReader(opened, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add) }

        // Asked over and over while the page loads, so some request lands before the book is open.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            compose.pageAction(R.string.reader_next_page)
            when (val message = messages.poll(50, TimeUnit.MILLISECONDS)) {
                ReaderMessage.Restored -> return
                is ReaderMessage.Failed -> throw AssertionError("The book failed to open: $message")
                else -> Unit
            }
        }
        throw AssertionError("The book did not open")
    }
}
