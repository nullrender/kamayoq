package app.kamayoq.core.reader

/**
 * How the book flows on screen: Pages or Scroll, by the fork's name. The fork misplaces the page
 * when the flow changes on an open book (piece 14, 03/11/2026), so a new flow reopens the book at
 * the saved CFI instead.
 */
enum class ReaderFlow(val wire: String) {
    PAGES("paginated"),
    SCROLL("scrolled");

    companion object {
        // Paginated columns expose off-screen text to screen readers; scrolling keeps reading order.
        fun forAccessibility(touchExploration: Boolean): ReaderFlow = if (touchExploration) SCROLL else PAGES
    }
}
