package app.kamayoq.android

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.ReaderMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Piece 11. The interceptor answers every request, so with it on "the server got 0 requests" would
// prove nothing: these tests leave it off and look at the WebView's own settings.
@RunWith(AndroidJUnit4::class)
class ReaderNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val requests = AtomicInteger()
    private lateinit var server: ServerSocket

    @Before
    fun startServer() {
        // Not getLoopbackAddress(): on Android it is ::1, and the page asks for 127.0.0.1 (the control
        // caught this on 03/11/2026: the server could not have received anything).
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                requests.incrementAndGet()
                socket.use { it.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
        }
    }

    @After
    fun stopServer() = server.close()

    /** A page on the test server's own origin, so mixed-content rules play no part. */
    private fun requestsFromPage(blockNetwork: Boolean): Int {
        val base = "http://127.0.0.1:${server.localPort}/"
        val page = """<!doctype html><img src="${base}image.png"><link rel="stylesheet" href="${base}style.css">"""
        val loaded = CompletableFuture<Unit>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.applyReaderSettings()
                webView.settings.blockNetworkLoads = blockNetwork
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.complete(Unit)
                    }
                }
                webView.loadDataWithBaseURL(base, page, "text/html", "UTF-8", null)
            }
            loaded.get(60, TimeUnit.SECONDS)
            Thread.sleep(2_000)
            scenario.onActivity { webView.destroy() }
        }
        return requests.get()
    }

    @Test
    fun `dado o controle com a rede liberada e sem interceptador quando a página pede então o servidor local recebe pedidos`() {
        assertTrue(requestsFromPage(blockNetwork = false) > 0)
    }

    @Test
    fun `dado as configurações do leitor sem interceptador quando a página pede então o servidor local recebe 0 pedidos`() {
        assertEquals(0, requestsFromPage(blockNetwork = true))
    }

    @Test
    fun `dado o leitor aberto quando a página tenta sair para outro endereço então a navegação é negada e o leitor continua`() {
        val messages = LinkedBlockingQueue<ReaderMessage>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            lateinit var client: ReaderClient
            scenario.onActivity { activity ->
                client = ReaderClient(activity.assets, book = null)
                webView = WebView(activity)
                activity.setContentView(webView)
                webView.loadReaderPage(client, messages::add)
            }
            assertEquals(ReaderMessage.Ready, messages.poll(60, TimeUnit.SECONDS))
            fun evaluate(script: String): String {
                val answer = CompletableFuture<String>()
                instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
                return answer.get(30, TimeUnit.SECONDS)
            }

            evaluate("location.href = 'https://exemplo.invalid/PWNED'; null")
            Thread.sleep(2_000)

            assertEquals("true", evaluate("document.querySelector('foliate-view') !== null"))
            assertEquals(listOf("https://exemplo.invalid/PWNED"), client.refusedNavigations)
            scenario.onActivity { webView.destroy() }
        }
    }
}
