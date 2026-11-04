package app.kamayoq.core.reader

import app.kamayoq.core.progress.TextPosition
import java.time.Instant

/**
 * One opening of a book in the text reader, from the saved position to each page the person moves
 * to. The fork reports positions while it lays out and restores, and again after every re-layout,
 * and none of those is the person reading: taking them would drift the saved place, so nothing is
 * accepted before the restore is confirmed, and after that only the person's moves.
 */
class TextReading(saved: TextPosition?, private val device: String) {
    enum class Phase { OPENING, RESTORED, READING }

    /** The CFI to hand the fork; null opens the book at its start. */
    val start: String? = saved?.cfi

    var phase: Phase = Phase.OPENING
        private set

    var position: TextPosition? = saved
        private set

    fun restored() {
        if (phase == Phase.OPENING) phase = Phase.RESTORED
    }

    /** Returns the position to save, or null when this move does not change it. */
    fun relocated(message: ReaderMessage.Relocated, at: Instant): TextPosition? {
        if (phase == Phase.OPENING || !message.reason.byPerson) return null
        phase = Phase.READING
        position = TextPosition(message.section, message.sections, message.cfi, message.position, at, device)
        return position
    }
}
