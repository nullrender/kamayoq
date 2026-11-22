package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.TextReading
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 5: the hint on the first opening in Pages shows once, and never in Scroll.
@RunWith(AndroidJUnit4::class)
class TextReaderHintTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var archive: EpubArchive? = null
    private var file: File? = null
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val seen = AtomicInteger()

    private fun open(type: WorkType) {
        val book = File(compose.activity.cacheDir, "hint.epub").apply { writeBytes(EpubFixtures.short()) }.also { file = it }
        val opened = EpubArchive.open(book).also { archive = it }
        compose.setContent {
            TextReader(
                opened, "Livro", TextReading(null, "emulator"), onSave = {}, onBack = {}, onMessage = messages::add, type = type,
                pagesHint = true, onPagesHintSeen = { seen.incrementAndGet() },
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
    fun close() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        archive?.close()
        file?.delete()
    }

    private fun shown(id: Int) = compose.onAllNodesWithText(compose.activity.getString(id)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `dado um livro em páginas aberto pela primeira vez quando toco em Entendi então a dica some e fica vista`() {
        open(WorkType.BOOKS)
        compose.waitForIdle()
        assertEquals(true, shown(R.string.reader_hint_ok))

        compose.onNodeWithText(compose.activity.getString(R.string.reader_hint_ok)).performClick()
        compose.waitForIdle()

        assertEquals(false, shown(R.string.reader_hint_ok))
        assertEquals(1, seen.get())
    }

    @Test
    fun `dado uma novel em rolagem então a dica das páginas não aparece`() {
        open(WorkType.NOVELS)
        compose.waitForIdle()
        assertEquals(false, shown(R.string.reader_hint_ok))
    }
}
