package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

// Piece 17: the screen the app shows for a book, with the same page and defenses as pieces 6 to 14.
@RunWith(AndroidJUnit4::class)
class TextReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun firstAnswer(messages: LinkedBlockingQueue<ReaderMessage>): ReaderMessage? {
        while (true) {
            when (val message = messages.poll(60, TimeUnit.SECONDS)) {
                ReaderMessage.Ready, is ReaderMessage.Relocated -> Unit
                else -> return message
            }
        }
    }

    private fun showing(book: ByteArray, check: (LinkedBlockingQueue<ReaderMessage>) -> Unit) {
        val file = File(context.cacheDir, "text-reader.epub").apply { writeBytes(book) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TextReader(archive, title = "Dom Casmurro", onBack = {}, onMessage = messages::add) }
                }
                check(messages)
            }
        }
    }

    @Test
    fun `dado um capítulo EPUB quando a tela do leitor abre então o livro aparece no início`() = showing(EpubFixtures.short()) { messages ->
        when (val answer = firstAnswer(messages)) {
            ReaderMessage.Restored -> Unit
            null -> fail("The book never showed")
            else -> fail("Reader said: $answer")
        }
    }

    @Test
    fun `dado um livro de layout fixo quando a tela do leitor abre então ela diz que ainda não abre`() = showing(EpubFixtures.fixedLayout()) { messages ->
        assertEquals(ReaderMessage.Failed("fixed-layout"), firstAnswer(messages))
    }
}
