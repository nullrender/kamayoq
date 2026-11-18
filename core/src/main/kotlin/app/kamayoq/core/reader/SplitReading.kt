package app.kamayoq.core.reader

import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import java.time.Instant

/**
 * One opening of a file split by its table of contents: reading crosses its chapters with no reload
 * (`docs/decisions/novel-chapters-2026-11-18.md`, section 3). [saved] is the progress of each chapter
 * of the file, in the order of the contents, and [opened] the one the person chose. As in
 * [TextReading], nothing is accepted before the restore is confirmed, and after that only the
 * person's moves.
 */
class SplitReading(saved: List<ChapterProgress>, opened: Int, private val device: String) {
    init {
        require(opened in saved.indices) { "Chapter $opened is outside the file's ${saved.size}" }
    }

    /** The progress of one chapter to save, by its place in the file. */
    data class Saved(val chapter: Int, val progress: ChapterProgress)

    private val progress = saved.toMutableList()

    // Lives with the opening, as in the Strip: a new opening may count a new complete read.
    private val counted = BooleanArray(saved.size)

    private var phase = TextReading.Phase.OPENING

    /** The chapter being read: where the work reopens. */
    var current: Int = opened
        private set

    // The chapter the person chose to read from, by opening it or by a jump. Paging back into the
    // end of an earlier one and forward again is a look, not a reading: it finishes nothing.
    private var readFrom = opened

    private val openedAt = (saved[opened].position as? TextPosition)?.takeIf { it.fraction.value < BasisPoints.END }

    /**
     * The CFI to hand the fork. Null opens the chapter at its start, by its key: one never opened,
     * and one whose last reading reached its end, as the Strip does.
     */
    val start: String? = openedAt?.cfi

    /** The section of [start], for a CFI the file no longer resolves. */
    val startSection: Int? = openedAt?.section

    fun progressOf(chapter: Int): ChapterProgress = progress[chapter]

    fun restored() {
        if (phase == TextReading.Phase.OPENING) phase = TextReading.Phase.RESTORED
    }

    /**
     * What to save after this move, in order, the chapter being read last; empty when the move
     * changes nothing. Paging or scrolling from a chapter into a later one finishes every chapter
     * left behind; a jump by the contents, or going back, finishes none.
     */
    fun relocated(message: ReaderMessage.Relocated, at: Instant): List<Saved> {
        if (phase == TextReading.Phase.OPENING || !message.reason.byPerson) return emptyList()
        phase = TextReading.Phase.READING
        val chapter = message.chapter?.takeIf { it in progress.indices } ?: current
        val saves = mutableListOf<Saved>()
        if (chapter > current && message.reason.turns) {
            for (left in maxOf(current, readFrom) until chapter) saves += finish(left, at, message)
        }
        if (!message.reason.turns) readFrom = chapter
        current = chapter
        // A file whose chapter fractions the page cannot measure keeps the start; the CFI is the place.
        // The end is kept for a chapter left behind or finished: the last page of a chapter measures
        // the end too, and a place kept there must reopen on that page, not at the chapter's start.
        val fraction = BasisPoints.fromStored(minOf(message.chapterPosition?.value ?: BasisPoints.START, BasisPoints.END - 1))
        progress[chapter] = progress[chapter].copy(position = place(message, fraction, at))
        saves += Saved(chapter, progress[chapter])
        return saves
    }

    /**
     * The person went past the last page of the file and the end block shows: the chapter being read
     * is finished. Null when this changes nothing.
     */
    fun ended(at: Instant): Saved? {
        if (phase == TextReading.Phase.OPENING || counted[current]) return null
        val before = progress[current]
        val end = (before.position as? TextPosition)?.copy(fraction = BasisPoints.fromStored(BasisPoints.END), at = at, device = device)
        counted[current] = true
        progress[current] = before.copy(
            readMark = before.readMark ?: ReadMark(at, ReadMarkSource.READ_TO_END),
            position = end ?: before.position,
            completeReads = before.completeReads + 1,
        )
        return Saved(current, progress[current])
    }

    /** Left forward: read, at its end, with the place of the first page past it. Counted once per opening. */
    private fun finish(chapter: Int, at: Instant, message: ReaderMessage.Relocated): Saved {
        val before = progress[chapter]
        progress[chapter] = before.copy(
            readMark = before.readMark ?: ReadMark(at, ReadMarkSource.READ_TO_END),
            position = place(message, BasisPoints.fromStored(BasisPoints.END), at),
            completeReads = before.completeReads + if (counted[chapter]) 0 else 1,
        )
        counted[chapter] = true
        return Saved(chapter, progress[chapter])
    }

    private fun place(message: ReaderMessage.Relocated, fraction: BasisPoints, at: Instant) =
        TextPosition(message.section, message.sections, message.cfi, fraction, at, device)
}
