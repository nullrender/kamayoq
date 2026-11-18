package app.kamayoq.core.reader

import app.kamayoq.core.progress.ChapterProgress
import java.time.Instant

/**
 * One opening of a file split by its table of contents: reading crosses its chapters with no reload
 * (`docs/decisions/novel-chapters-2026-11-18.md`, section 3). [saved] is the progress of each chapter
 * of the file, in the order of the contents, and [opened] the one the person chose.
 */
class SplitReading(saved: List<ChapterProgress>, opened: Int, private val device: String) {
    /** The progress of one chapter to save, by its place in the file. */
    data class Saved(val chapter: Int, val progress: ChapterProgress)

    private val progress = saved

    /** The chapter being read: where the work reopens. */
    val current: Int = opened

    /** The CFI to hand the fork; null opens the chapter at its start, by its key. */
    val start: String? = null

    /** The section of [start], for a CFI the file no longer resolves. */
    val startSection: Int? = null

    fun progressOf(chapter: Int): ChapterProgress = progress[chapter]

    fun restored() = Unit

    /** What to save after this move, in order, the chapter being read last. */
    fun relocated(message: ReaderMessage.Relocated, at: Instant): List<Saved> = emptyList()

    /** The end block shows: the chapter being read is finished. Null when this changes nothing. */
    fun ended(at: Instant): Saved? = null
}
