package app.kamayoq.core.reader

/** A native contents button refers to an index, never JavaScript made from a book's href. */
data class ReaderContentsItem(val index: Int, val title: String, val depth: Int)

object ReaderContents {
    const val MAX_ITEMS = 5_000
    const val MAX_DEPTH = 16
    const val MAX_TITLE = 512
    const val MAX_JSON = 4 * 1024 * 1024

    /** Null means the page did not provide a valid bounded list; an empty list means no contents. */
    fun parse(raw: String): List<ReaderContentsItem>? = null
}
