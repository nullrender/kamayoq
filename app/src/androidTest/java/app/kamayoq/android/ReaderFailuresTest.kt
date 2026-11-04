package app.kamayoq.android

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Piece 13: what the reader cannot do ends in a message, never in a crash or a blank page.
@RunWith(AndroidJUnit4::class)
class ReaderFailuresTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun `dado o renderizador morto quando a página cai então o app não cai e o leitor fica sabendo`() {
        val gone = CountDownLatch(1)
        val messages = LinkedBlockingQueue<ReaderMessage>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.loadReaderPage(ReaderClient(activity.assets, book = null, onRendererGone = gone::countDown), messages::add)
            }
            assertEquals(ReaderMessage.Ready, messages.poll(60, TimeUnit.SECONDS))

            // The official way to end a renderer (API 29+), instead of a crash page that may not exist.
            scenario.onActivity { webView.webViewRenderProcess?.terminate() }

            assertTrue("The reader never heard of the dead renderer", gone.await(30, TimeUnit.SECONDS))
            // The app's process is still here to answer: a crash would have ended the test run.
            scenario.onActivity { activity ->
                activity.setContentView(android.view.View(activity))
                webView.destroy()
            }
        }
    }

    // An exception inside the WebView's request thread ends the app.
    @Test
    fun `dado o arquivo do livro já fechado quando a página pede uma entrada então a resposta é 404 e o app continua`() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "closed.epub").apply { writeBytes(EpubFixtures.short()) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        val archive = EpubArchive.open(file)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            lateinit var client: ReaderClient
            scenario.onActivity { activity ->
                client = ReaderClient(activity.assets, ReaderBook(archive, token = "closed1", position = null))
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.loadReaderPage(client, messages::add)
            }
            check(generateSequence { messages.poll(30, TimeUnit.SECONDS) }.any { it is ReaderMessage.Relocated }) { "The book never opened" }

            archive.close()
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    fetch('/book/closed1/e/OEBPS/s2.xhtml').then(response => kamayoqBridge.postMessage(JSON.stringify(
                        { type: 'failed', code: response.status === 404 ? 'got-not-found' : 'got-something-else' })))
                    """.trimIndent(),
                    null,
                )
            }

            val answer = generateSequence { messages.poll(30, TimeUnit.SECONDS) }.firstOrNull { it is ReaderMessage.Failed }
            assertEquals(ReaderMessage.Failed("got-not-found"), answer)
            assertTrue(client.refusedRequests.toString(), client.refusedRequests.any { it.endsWith("/e/OEBPS/s2.xhtml") })
            scenario.onActivity { webView.destroy() }
        }
    }

    // The fork's failures to open are asynchronous: this is the path they take to the app.
    @Test
    fun `dado um EPUB cujo pacote não existe quando abro então o leitor avisa page-error em vez de ficar em branco`() {
        val context = instrumentation.targetContext
        val broken = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
                zip.write(
                    """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/missing.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                        .toByteArray(),
                )
                zip.closeEntry()
            }
        }.toByteArray()
        val file = File(context.cacheDir, "broken.epub").apply { writeBytes(broken) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                scenario.onActivity { activity ->
                    webView = WebView(activity)
                    activity.setContentView(webView)
                    webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(archive, token = "broken1", position = null)), messages::add)
                }

                val answer = generateSequence { messages.poll(30, TimeUnit.SECONDS) }.firstOrNull { it != ReaderMessage.Ready }

                assertEquals(ReaderMessage.Failed("page-error"), answer)
                scenario.onActivity { webView.destroy() }
            }
        }
    }

    @Test
    fun `dado um EPUB de página fixa quando abro então o leitor diz que não abre esse tipo em vez de falhar calado`() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fixed.epub").apply { writeBytes(EpubFixtures.fixedLayout()) }
        val messages = LinkedBlockingQueue<ReaderMessage>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                scenario.onActivity { activity ->
                    webView = WebView(activity)
                    activity.setContentView(webView)
                    webView.loadReaderPage(ReaderClient(activity.assets, ReaderBook(archive, token = "fixed1", position = null)), messages::add)
                }

                // The first answer after "ready" says what happened to the book.
                val answer = generateSequence { messages.poll(30, TimeUnit.SECONDS) }.firstOrNull { it != ReaderMessage.Ready }

                assertEquals(ReaderMessage.Failed("fixed-layout"), answer)
                scenario.onActivity { webView.destroy() }
            }
        }
    }
}
