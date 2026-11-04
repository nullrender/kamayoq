package app.kamayoq.android

import android.content.res.AssetManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.epubfixtures.EpubFixtures
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The reader without its CSP, so the frame's sandbox is tested on its own. */
open class SandboxOnlyClient(assets: AssetManager, book: ReaderBook) : ReaderClient(assets, book) {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        responseFor(request.url.toString()).apply {
            responseHeaders = responseHeaders.orEmpty() - "Content-Security-Policy"
        }
}

/**
 * The page with the reader's defenses taken off, for the emulator only: if the hostile book leaves
 * no trace here, its attacks do not work and a clean reader would prove nothing. Besides the CSP, it
 * gives the fork's frame back the `allow-scripts` that third_party/foliate-js/UPSTREAM.md took out,
 * and lets `<meta http-equiv>` through again (piece 12).
 */
class ControlClient(assets: AssetManager, book: ReaderBook) : SandboxOnlyClient(assets, book) {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val response = super.shouldInterceptRequest(view, request)
        val url = request.url.toString()
        when {
            // If the line ever changes shape, nothing is replaced and the control test fails on its own.
            url.endsWith("/foliate-js/paginator.js") -> {
                val patched = response.data.use { it.readBytes().decodeToString() }
                response.data = patched.replace(KAMAYOQ_SANDBOX, ORIGINAL_SANDBOX).byteInputStream()
            }
            url.endsWith("/reader/sections.js") -> response.data = NO_NEUTRALIZING.byteInputStream()
        }
        return response
    }

    private companion object {
        const val KAMAYOQ_SANDBOX = "setAttribute('sandbox', 'allow-same-origin')"
        const val ORIGINAL_SANDBOX = "setAttribute('sandbox', 'allow-same-origin allow-scripts')"
        const val NO_NEUTRALIZING = "export const neutralizeRefresh = text => text\n"
    }
}

/** Opens [EpubFixtures.hostile] in a real window, walks every section and gathers the traces. */
class HostileBookRun(private val makeClient: (AssetManager, ReaderBook) -> ReaderClient) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val messages = LinkedBlockingQueue<ReaderMessage>()
    private val cases = EpubFixtures.HOSTILE_SCRIPTS + EpubFixtures.HOSTILE_REQUESTS

    /** The name of every case that left a trace. */
    fun traces(): Set<String> {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "hostile.epub").apply { writeBytes(EpubFixtures.hostile()) }
        val found = mutableSetOf<String>()
        EpubArchive.open(file).use { archive ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                lateinit var client: ReaderClient
                scenario.onActivity { activity ->
                    client = makeClient(activity.assets, ReaderBook(archive, token = "hostile1", position = null))
                    webView = WebView(activity)
                    activity.setContentView(webView)
                    webView.loadReaderPage(client, messages::add)
                }
                fun evaluate(script: String): String {
                    val answer = CompletableFuture<String>()
                    instrumentation.runOnMainSync { webView.evaluateJavascript(script, answer::complete) }
                    return answer.get(30, TimeUnit.SECONDS)
                }
                val seen = mutableListOf<ReaderMessage>()
                fun arrived(seconds: Long, predicate: (ReaderMessage) -> Boolean): Boolean {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
                    while (System.nanoTime() < deadline) {
                        val message = messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        seen += message
                        if (predicate(message)) return true
                    }
                    return false
                }
                // A walk that never reached a section would find no trace and pass: each section must
                // say it opened. The paginator drops a jump while busy,
                // so a jump with no answer is sent again.
                check(arrived(60) { it is ReaderMessage.Relocated }) { "The hostile book never opened: $seen" }
                for ((index, case) in cases.withIndex()) {
                    var reached = false
                    for (attempt in 1..6) {
                        evaluate("document.querySelector('foliate-view').goTo($index).catch(() => {}); null")
                        reached = arrived(5) { it is ReaderMessage.Relocated && it.section == index }
                        if (reached) break
                    }
                    check(reached) { "Section $index ($case) never opened: $seen" }
                    // Long enough for the section to run what it can and ask for what it wants.
                    Thread.sleep(SECTION_WAIT_MS)
                    Regex("PWNED-([a-z-]+)").find(evaluate("document.title"))?.let { found += it.groupValues[1] }
                    val secondReader = evaluate(
                        "document.querySelector('foliate-view').renderer.getContents().some(c => c.doc.querySelector('foliate-view') !== null)",
                    )
                    if (secondReader == "true") found += "reader-script"
                }
                Thread.sleep(SECTION_WAIT_MS)
                seen += generateSequence { messages.poll() }
                // A reader that broke while opening would also leave no trace.
                check(ReaderMessage.Failed("page-error") !in seen) { "The reader page failed: $seen" }
                for (message in seen) {
                    when {
                        message is ReaderMessage.Failed && message.code.startsWith("pwned-") -> found += message.code.removePrefix("pwned-")
                        message == ReaderMessage.Refused("not the main frame") -> found += "reader-script"
                    }
                }
                for (url in client.refusedRequests) {
                    Regex("PWNED-([a-z-]+?)(\\.png|\\.css)?$").find(url)?.let { found += it.groupValues[1] }
                }
                scenario.onActivity { webView.destroy() }
            }
        }
        return found
    }

    private companion object {
        const val SECTION_WAIT_MS = 3_000L
    }
}
