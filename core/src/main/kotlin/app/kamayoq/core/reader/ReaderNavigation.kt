package app.kamayoq.core.reader

/**
 * Where the reader's frames may go. The page itself never leaves (a link out is the app's to open,
 * after asking); a frame may only become a section of the book, which the fork loads by `srcdoc`
 * or, for a nested document, by a `blob:` of the page's own origin.
 */
object ReaderNavigation {
    fun allows(url: String, isMainFrame: Boolean): Boolean =
        !isMainFrame && (url == "about:srcdoc" || url.startsWith("blob:${ReaderPolicy.ORIGIN}/"))
}
