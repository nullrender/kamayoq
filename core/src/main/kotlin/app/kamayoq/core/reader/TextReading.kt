package app.kamayoq.core.reader

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.TextPosition
import java.time.Instant

/**
 * One opening of a book in the text reader, from the saved progress to each page the person moves
 * to. The fork reports positions while it lays out and restores, and again after every re-layout,
 * and none of those is the person reading: taking them would drift the saved place, so nothing is
 * accepted before the restore is confirmed, and after that only the person's moves.
 */
class TextReading(saved: ChapterProgress, private val device: String) {
    /** An opening that only knows a place, as the tests of the place alone use. */
    constructor(saved: TextPosition?, device: String) : this(ChapterProgress(readMark = null, position = saved, completeReads = 0), device)

    enum class Phase { OPENING, RESTORED, READING }

    /** The CFI to hand the fork; null opens the book at its start. */
    val start: String? = (saved.position as? TextPosition)?.cfi

    var phase: Phase = Phase.OPENING
        private set

    /** The whole progress to save: the place, the read mark and the complete reads. */
    var progress: ChapterProgress = saved
        private set

    val position: TextPosition? get() = progress.position as? TextPosition

    fun restored() {
        if (phase == Phase.OPENING) phase = Phase.RESTORED
    }

    /** Returns the position to save, or null when this move does not change it. */
    fun relocated(message: ReaderMessage.Relocated, at: Instant): TextPosition? {
        if (phase == Phase.OPENING || !message.reason.byPerson) return null
        phase = Phase.READING
        val position = TextPosition(message.section, message.sections, message.cfi, message.position, at, device)
        progress = progress.copy(position = position)
        return position
    }

    /**
     * The person went past the last page and the end block shows (decision 9): returns the progress
     * to save, or null when this changes nothing.
     */
    fun ended(at: Instant): ChapterProgress? = null
}
