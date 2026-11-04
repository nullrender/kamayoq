package app.kamayoq.core.reader

/** What the text reader needs from the phone's WebView. */
object ReaderEngine {
    /** `Object.groupBy` and `Map.groupBy`, which the fork calls, arrived in Chromium 117 (mdn/browser-compat-data). */
    const val MINIMUM_WEBVIEW = 117

    /** A version that cannot be read is refused: the reader would fail later, with a worse message. */
    fun supports(webViewVersion: String?): Boolean {
        val major = webViewVersion?.substringBefore('.')?.toIntOrNull() ?: return false
        return major >= MINIMUM_WEBVIEW
    }
}

/**
 * A dead renderer leaves a WebView that cannot be used again. Reloading the same page can crash the
 * same way (Android's guide on managing WebView), so the reader reopens once and then stops.
 */
class RendererCrashes {
    enum class Decision { REOPEN, STOP }

    private var count = 0

    fun afterCrash(): Decision = if (++count == 1) Decision.REOPEN else Decision.STOP
}
