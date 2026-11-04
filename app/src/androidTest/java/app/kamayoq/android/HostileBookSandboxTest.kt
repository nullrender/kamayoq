package app.kamayoq.android

import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Piece 10: the book's frame without allow-scripts. Valid only while HostileBookControlTest, which
// puts allow-scripts back, still shows every trace.
@RunWith(AndroidJUnit4::class)
class HostileBookSandboxTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun `dado o leitor completo quando percorro o livro hostil então nenhum rastro aparece`() {
        assertEquals(emptySet<String>(), HostileBookRun(::ReaderClient).traces())
    }

    @Test
    fun `dado só o sandbox sem a CSP quando percorro o livro hostil então nenhum script roda`() {
        val traces = HostileBookRun(::SandboxOnlyClient).traces()

        assertEquals(emptySet<String>(), traces intersect EpubFixtures.HOSTILE_SCRIPTS.toSet())
    }

    @Test
    fun `dado o texto do livro quando pressiono longamente uma palavra então ela fica selecionada`() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "selection.epub").apply { writeBytes(EpubFixtures.short()) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                scenario.onActivity { activity ->
                    webView = WebView(activity)
                    activity.setContentView(webView)
                    webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(archive, token = "select1", position = null)), messages::add)
                }
                while (messages.poll(60, TimeUnit.SECONDS)?.let { it !is ReaderMessage.Relocated } ?: error("No position arrived")) Unit
                fun evaluate(script: String): String {
                    val answer = CompletableFuture<String>()
                    instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
                    return answer.get(30, TimeUnit.SECONDS)
                }
                touchScreen(webView.screenPointOf("p"), holdMs = 1_500)
                SystemClock.sleep(1_000)

                val selected = evaluate(
                    "document.querySelector('foliate-view').renderer.getContents()[0].doc.getSelection().toString()",
                )
                assertNotNull(selected)
                assertTrue("Selected: $selected", selected.trim('"').isNotBlank())
                scenario.onActivity { webView.destroy() }
            }
        }
    }
}
