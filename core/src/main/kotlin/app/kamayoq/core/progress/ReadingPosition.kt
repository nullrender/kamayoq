package app.kamayoq.core.progress

import java.time.Instant

/** Where the person is in a chapter: an image in the Strip, or a place in a book read as text. */
sealed interface ReadingPosition {
    val at: Instant
    val device: String
}

/**
 * This position as the Strip's, for code that only knows images (the Strip, Kavita's pages, the
 * positions kept aside). A book's position reaching it is a bug: turning a section into a page would
 * move the person without a word, so it stops here instead.
 */
fun ReadingPosition.strip(): StripPosition =
    this as? StripPosition ?: error("A book's position reached code that only reads images")
