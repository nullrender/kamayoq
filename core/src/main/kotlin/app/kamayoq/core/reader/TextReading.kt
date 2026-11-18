package app.kamayoq.core.reader

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.KavitaDevice
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import java.time.Instant

/**
 * One opening of a book in the text reader, from the saved progress to each page the person moves
 * to. The fork reports positions while it lays out and restores, and again after every re-layout,
 * and none of those is the person reading: taking them would drift the saved place, so nothing is
 * accepted before the restore is confirmed, and after that only the person's moves.
 */
class TextReading(saved: ChapterProgress, private val device: String, savedMarker: String? = null) {
    /** An opening that only knows a place, as the tests of the place alone use. */
    constructor(saved: TextPosition?, device: String) : this(ChapterProgress(readMark = null, position = saved, completeReads = 0), device)

    enum class Phase { OPENING, RESTORED, READING }

    private val fromKavita = (saved.position as? TextPosition)?.takeIf { it.device == KavitaDevice.NAME }

    /**
     * The CFI to hand the fork; null opens the book at its start, or at [startMarker]. A place that
     * came from Kavita has only its section in the CFI: it opens by section and marker instead.
     */
    val start: String? = (saved.position as? TextPosition)?.takeIf { fromKavita == null }?.cfi

    /** The marker a place from Kavita opens at, in its section; null for any other place. */
    val startMarker: String? = savedMarker?.takeIf { fromKavita != null }

    /** How many sections Kavita counts for a place that came from it, for the reader to compare. */
    val startSections: Int? = fromKavita?.sections

    /**
     * Kavita's marker of the place to save, from the same move as [position] (milestone C2). It starts
     * as the one known for the saved place, from here or from Kavita: the end block may save that
     * place again with no move first.
     */
    var marker: String? = savedMarker
        private set

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
        marker = message.marker
        return position
    }

    // Lives with the opening, as in the Strip: a new opening may count a new complete read.
    private var endCounted = false

    /**
     * The person went past the last page and the end block shows (decision 9): returns the progress
     * to save, or null when this changes nothing. Only this marks the book read, never the fraction,
     * and only once per opening; a book read before keeps its first mark.
     */
    fun ended(at: Instant): ChapterProgress? {
        if (phase == Phase.OPENING || endCounted) return null
        endCounted = true
        progress = progress.copy(
            readMark = progress.readMark ?: ReadMark(at, ReadMarkSource.READ_TO_END),
            completeReads = progress.completeReads + 1,
        )
        return progress
    }
}
