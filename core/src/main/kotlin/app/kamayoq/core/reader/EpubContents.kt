package app.kamayoq.core.reader

/** One item of a book's table of contents, as the fork lists it; [subitems] is empty for a leaf. */
data class ContentsEntry(val label: String, val href: String?, val subitems: List<ContentsEntry>)

/**
 * A chapter of a file split by its table of contents (`docs/decisions/novel-chapters-2026-11-18.md`).
 * [key] is the item's destination, a path inside the EPUB with its fragment; [volume] the title of
 * the level above, when there is one.
 */
data class ContentsChapter(val title: String, val key: String, val volume: String?)

/**
 * A book's table of contents and the chapters it gives. No [chapters] means the file does not carry
 * the division: it is one chapter, the whole file. [chapterOfItem] says, for the number the fork
 * gives each item (counted from 0, parents before their subitems), which chapter a page under that
 * item is in.
 */
data class BookContents(
    val entries: List<ContentsEntry>,
    val chapters: List<ContentsChapter>,
    val chapterOfItem: List<Int>,
) {
    companion object {
        val UNDIVIDED = BookContents(emptyList(), emptyList(), emptyList())
    }
}

/** Reads a book's table of contents the way the fork does. */
object EpubContents {
    /** [load] gives the text of the entry with that exact name, or null when there is none. */
    fun read(load: (String) -> String?): BookContents = BookContents.UNDIVIDED
}
