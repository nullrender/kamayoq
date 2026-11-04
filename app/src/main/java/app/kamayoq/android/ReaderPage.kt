package app.kamayoq.android

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.kamayoq.core.reader.EpubArchive
import app.kamayoq.core.reader.OpenBook
import app.kamayoq.core.reader.ReaderBookIndex
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReaderMessage
import app.kamayoq.core.reader.ReaderNavigation
import app.kamayoq.core.reader.ReaderPolicy
import app.kamayoq.core.reader.ReaderRoute
import app.kamayoq.core.reader.ReaderRoutes
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Everything under `/reader/` that the page may ask for: the assets of the app, fixed in the APK. */
private val READER_FILES: Set<String> = setOf("index.html", "errors.js", "reader.js", "sections.js", "messages.js") + listOf(
    "view",
    "epub",
    "epubcfi",
    "paginator",
    "section-chunks",
    "progress",
    "overlayer",
    "text-walker",
).map { "foliate-js/$it.js" }

private const val READER_PAGE = "${ReaderPolicy.ORIGIN}/reader/index.html"

/** The name the page sees; the same in `errors.js` and `reader.js`. */
private const val BRIDGE = "kamayoqBridge"

/** The book the page may read; [token] goes in its addresses, new at each opening. */
class ReaderBook(val archive: EpubArchive, val token: String, val position: String?, val flow: ReaderFlow = ReaderFlow.PAGES)

/**
 * Answers every request of the page, so none of them ever falls through to the network. Open only so
 * the emulator tests can build a control page without the defenses; the app never does.
 */
open class ReaderClient(
    private val assets: AssetManager,
    private val book: ReaderBook?,
    private val onRendererGone: () -> Unit = {},
) : WebViewClient() {
    private val routes = ReaderRoutes(READER_FILES, book?.let { OpenBook(it.token, it.archive.names) })

    /** Every address the page asked for and did not get, so a refusal can be told from no attempt. */
    val refusedRequests: List<String> get() = refused
    private val refused = CopyOnWriteArrayList<String>()

    /** Every place the page or a frame tried to go and was kept from. */
    val refusedNavigations: List<String> get() = refusedPlaces
    private val refusedPlaces = CopyOnWriteArrayList<String>()

    /** Given to the page when it says it is ready. */
    val token: String? = book?.token

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        responseFor(request.url.toString())

    /**
     * Handled, so the app lives on; the dead WebView cannot be used again, and whoever owns it decides
     * with [app.kamayoq.core.reader.RendererCrashes] whether to build a new one.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone()
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val allowed = ReaderNavigation.allows(url, request.isForMainFrame)
        if (!allowed) refusedPlaces += url
        return !allowed
    }

    fun responseFor(url: String): WebResourceResponse = when (val route = routes.decide(url)) {
        is ReaderRoute.ReaderFile -> WebResourceResponse(
            typeOf(route.path),
            "UTF-8",
            200,
            "OK",
            // By header, not <meta>: a <meta> policy misses what came before it (CSP3, section 3.3).
            if (route.path == "index.html") mapOf("Content-Security-Policy" to ReaderPolicy.contentSecurityPolicy) else emptyMap(),
            assets.open("reader/${route.path}"),
        )
        ReaderRoute.BookIndex -> bookFile(ByteArrayInputStream(indexOf(requireNotNull(book)).toByteArray()))
        is ReaderRoute.BookEntry -> try {
            bookFile(requireNotNull(book).archive.open(route.name))
        } catch (_: IOException) {
            failed(url)
        } catch (_: IllegalStateException) {
            // The archive was closed while the page still asked for it.
            failed(url)
        }
        ReaderRoute.NotFound -> notFound().also { refused += url }
    }

    /**
     * This runs on the WebView's own request thread, where an exception ends the whole app (closing
     * review of 03/11/2026): an entry that cannot be read becomes a 404, kept in [refusedRequests].
     */
    private fun failed(url: String) = notFound().also { refused += url }

    private fun typeOf(path: String) = if (path.endsWith(".html")) "text/html" else "text/javascript"

    private fun indexOf(book: ReaderBook) =
        ReaderBookIndex.json(book.archive.names.associateWith(book.archive::sizeOf), book.position, book.flow)

    // Never a type a browser would run: a `<script src>` to a book file fails here too.
    private fun bookFile(content: InputStream) = WebResourceResponse(
        "application/octet-stream",
        null,
        200,
        "OK",
        mapOf("X-Content-Type-Options" to "nosniff"),
        content,
    )

    private fun notFound() =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/**
 * The page reaches files and the network only through [client]. The bridge exists for this origin
 * alone, and each message passes through [ReaderMessage.parse]; a refusal arrives like any message.
 */
fun WebView.loadReaderPage(client: ReaderClient, onMessage: (ReaderMessage) -> Unit) {
    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "WebView too old for the reader" }
    applyReaderSettings()
    webViewClient = client
    WebViewCompat.addWebMessageListener(this, BRIDGE, setOf(ReaderPolicy.ORIGIN)) { _, message, _, isMainFrame, reply ->
        // Not a defense against a script that runs (design, section 2): only the page speaks here.
        val parsed = when {
            !isMainFrame -> ReaderMessage.Refused("not the main frame")
            // Reading a binary message as text throws, and this runs on the main thread.
            message.type != WebMessageCompat.TYPE_STRING -> ReaderMessage.Refused("not text")
            else -> ReaderMessage.parse(message.data.orEmpty())
        }
        // An answer through the bridge, not a script: data never gets concatenated into code.
        if (parsed == ReaderMessage.Ready) client.token?.let(reply::postMessage)
        onMessage(parsed)
    }
    loadUrl(READER_PAGE)
}

/** Kept apart from [loadReaderPage] so the emulator tests can prove the network stays shut on its own. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyReaderSettings() {
    settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        // With the INTERNET permission the default is false, so the Kavita client would open the page's way out.
        blockNetworkLoads = true
    }
}

/** A fixed command: nothing from the book ever enters what is evaluated in the page. */
fun WebView.goToNextPage() = evaluateJavascript("kamayoqReader.next()", null)

fun WebView.goToPreviousPage() = evaluateJavascript("kamayoqReader.previous()", null)
