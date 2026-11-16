package app.kamayoq.core.progress

import java.time.Instant

/**
 * Where the person is in a book read as text (step 4, piece 14). [section] counts the spine from 0;
 * [cfi] is the fork's EPUB CFI of the visible page, and [fraction] how far into the book it is.
 */
data class TextPosition(
    val section: Int,
    val sections: Int,
    val cfi: String,
    val fraction: BasisPoints,
    override val at: Instant,
    override val device: String,
) : ReadingPosition {
    init {
        require(section in 0 until sections) { "Section $section is outside 0 until $sections" }
        require(cfi.startsWith("epubcfi(")) { "Not an EPUB CFI: $cfi" }
    }
}
