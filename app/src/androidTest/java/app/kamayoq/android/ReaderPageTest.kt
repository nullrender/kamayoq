package app.kamayoq.android

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.ReaderMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private lateinit var client: ReaderClient
    private lateinit var webView: WebView

    @Before
    fun openPage() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            client = ReaderClient(context.assets, book = null)
            webView = WebView(context)
            webView.loadReaderPage(client, messages::add)
        }
    }

    @After
    fun closePage() {
        instrumentation.runOnMainSync { webView.destroy() }
    }

    private fun nextMessage(): ReaderMessage? = messages.poll(60, TimeUnit.SECONDS)

    private fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `dado a página do leitor quando carrega então foliate-view existe e a mensagem ready chega ao Kotlin`() {
        assertEquals(ReaderMessage.Ready, nextMessage())
        assertEquals("true", evaluate("customElements.get('foliate-view') !== undefined && document.querySelector('foliate-view') !== null"))
    }

    // Since the CSP (piece 9), only /book/ is reachable by fetch, so a book that is not open is the
    // request that still reaches the interceptor.
    @Test
    fun `dado um pedido fora das rotas quando a página pede então o interceptador responde 404`() {
        assertEquals(ReaderMessage.Ready, nextMessage())

        evaluate(
            """
            fetch('/book/outro/index.json').then(response => kamayoqBridge.postMessage(JSON.stringify(
                { type: 'failed', code: response.status === 404 ? 'got-not-found' : 'got-something-else' })))
            null
            """.trimIndent(),
        )

        assertEquals(ReaderMessage.Failed("got-not-found"), nextMessage())
        assertEquals(listOf("https://appassets.androidplatform.net/book/outro/index.json"), client.refusedRequests)
    }

    @Test
    fun `dado um pedido fora do que a CSP permite quando a página pede então ele nem chega ao interceptador`() {
        assertEquals(ReaderMessage.Ready, nextMessage())

        evaluate(
            """
            fetch('/segredo').then(
                () => kamayoqBridge.postMessage(JSON.stringify({ type: 'failed', code: 'got-response' })),
                () => kamayoqBridge.postMessage(JSON.stringify({ type: 'failed', code: 'fetch-refused' })))
            null
            """.trimIndent(),
        )

        assertEquals(ReaderMessage.Failed("fetch-refused"), nextMessage())
        assertEquals(emptyList<String>(), client.refusedRequests)
    }

    // Without a viewport the WebView lays the page out 980 px wide and shrinks it to fit (03/11/2026).
    @Test
    fun `dado a página do leitor quando carrega então ela tem a largura da tela em dp`() {
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.use { scenario ->
            lateinit var shown: WebView
            val shownMessages = LinkedBlockingQueue<ReaderMessage>()
            scenario.onActivity {
                shown = WebView(it)
                it.setContentView(shown)
                shown.loadReaderPage(ReaderClient(it.assets, book = null), shownMessages::add)
            }
            assertEquals(ReaderMessage.Ready, shownMessages.poll(60, TimeUnit.SECONDS))
            val answer = CompletableFuture<String>()
            instrumentation.runOnMainSync { shown.evaluateJavascript("window.innerWidth", answer::complete) }
            val cssWidth = answer.get(30, TimeUnit.SECONDS).toFloat()
            var dpWidth = 0f
            instrumentation.runOnMainSync { dpWidth = shown.width / shown.resources.displayMetrics.density }

            assertEquals(dpWidth, cssWidth, 1f)
            scenario.onActivity { shown.destroy() }
        }
    }

    // Rejections are proven by ReaderFailuresTest's broken book, through the reader's own code: one
    // made by evaluateJavascript raised no unhandledrejection on the emulator, even from a timer
    // (two runs, 03/11/2026).
    @Test
    fun `dado um erro lançado na página quando acontece então ele chega ao Kotlin como page-error`() {
        assertEquals(ReaderMessage.Ready, nextMessage())

        evaluate("setTimeout(() => { throw new Error('x') }); null")

        assertEquals(ReaderMessage.Failed("page-error"), nextMessage())
    }

    // Reading a binary message as text throws in the listener.
    @Test
    fun `dado uma mensagem binária na ponte quando chega então ela é recusada e o app continua`() {
        assertEquals(ReaderMessage.Ready, nextMessage())

        evaluate("kamayoqBridge.postMessage(new ArrayBuffer(4)); null")

        assertEquals(ReaderMessage.Refused("not text"), nextMessage())
        assertEquals("true", evaluate("document.querySelector('foliate-view') !== null"))
    }

    @Test
    fun `dado uma URL de outra origem quando o interceptador responde então é 404 e nunca nulo`() {
        val response = client.responseFor("https://exemplo.invalid/reader/index.html")

        assertNotNull(response)
        assertEquals(404, response.statusCode)
    }

    @Test
    fun `dado uma mensagem de tipo desconhecido quando chega ao Kotlin então ela é recusada`() {
        assertEquals(ReaderMessage.Ready, nextMessage())

        evaluate("kamayoqBridge.postMessage('{\"type\":\"PWNED\"}'); null")

        assertEquals(ReaderMessage.Refused("unknown type"), nextMessage())
    }
}
