package app.kamayoq.core.reader

/** What the page of the text reader may do. Nothing here has run with the fork yet (step 4, piece 9). */
object ReaderPolicy {
    /** A private https origin: it answers only through the app's interceptor, never from the internet. */
    const val ORIGIN = "https://appassets.androidplatform.net"

    /**
     * Scripts only from the reader's folder, by path: `'self'` would run a `<script src>` that points
     * at the book's own files (measured in desktop Chromium on 30/10/2026). Styles stay inline because
     * the fork writes each book's CSS into the page.
     */
    val contentSecurityPolicy: String = listOf(
        "default-src 'none'",
        "script-src $ORIGIN/reader/",
        "style-src 'unsafe-inline' blob:",
        "img-src blob: data:",
        "font-src blob:",
        "media-src blob:",
        "connect-src $ORIGIN/book/",
        "frame-src blob:",
        "base-uri 'none'",
        "form-action 'none'",
        "object-src 'none'",
    ).joinToString("; ")
}
