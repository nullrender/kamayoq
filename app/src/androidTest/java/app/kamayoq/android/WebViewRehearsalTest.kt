package app.kamayoq.android

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Step 4, piece 1: proves what an emulator image can do, by function and not by version number.
// No commas in test names here: the DEX format refuses them, even though JVM tests accept them.
@RunWith(AndroidJUnit4::class)
class WebViewRehearsalTest {
    @Test
    fun `dado um WebView da imagem quando pergunto por Object groupBy e Map groupBy então os dois existem`() {
        val answer = evaluateAfterLoading(
            page = "<!doctype html><title>x</title>",
            script = "typeof Object.groupBy + ' ' + typeof Map.groupBy",
        )

        assertEquals("\"function function\"", answer)
    }

    @Test
    fun `dado uma página com JavaScript quando ela carrega então responde ao teste`() {
        val answer = evaluateAfterLoading(
            page = "<!doctype html><title>x</title><script>document.title = 'ready'</script>",
            script = "document.title",
        )

        assertEquals("\"ready\"", answer)
    }

    private fun evaluateAfterLoading(page: String, script: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(script) { result ->
                        answer.complete(result)
                        view.destroy()
                    }
                }
            }
            webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", page, "text/html", "UTF-8", null)
        }
        return answer.get(60, TimeUnit.SECONDS)
    }
}
