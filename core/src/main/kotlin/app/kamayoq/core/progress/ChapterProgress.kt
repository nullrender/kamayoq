package app.kamayoq.core.progress

import java.time.Instant

/** Where a read mark came from (progress contract, section 2b). */
enum class ReadMarkSource { READ_TO_END, PERSON, KAVITA }

data class ReadMark(val at: Instant, val source: ReadMarkSource)

/** The reading of one chapter (progress contract, section 2b). */
data class ChapterProgress(
    /** Null means not read. The position never removes it (C1). */
    val readMark: ReadMark?,
    /** The last place shown, also after the end and in a reread. Null means never opened. */
    val position: StripPosition?,
    /** Readings that reached the end on this device; marking read by hand does not count. */
    val completeReads: Int,
) {
    init {
        require(completeReads >= 0) { "Complete reads cannot be negative: $completeReads" }
    }

    companion object {
        val NEVER_OPENED = ChapterProgress(readMark = null, position = null, completeReads = 0)
    }
}
