package app.kamayoq.core.home

import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingPosition
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition

/** A chapter of a work, in reading order, with its progress. */
data class ChapterReading(val id: String, val progress: ChapterProgress)

/** Where Continue opens: [chapterId], and whether the reading goes on there or starts ([goesOn]). */
data class ContinueTarget(val chapterId: String, val goesOn: Boolean)

/** The chapter the Continue button of a work opens, on the Work screen and on Home. */
object ContinueTargets {

    /**
     * Read marks say where the reading goes on (his decision, 20/11/2026): when nothing was opened
     * yet but some chapter is read, or the chapter the reading stopped in ([resumeId]) is read, it
     * goes on at the first chapter not read, or at the last one when all are read. A chapter read
     * and opened again later, left before its end, is a reread: Continue goes back to it (contract,
     * case 5; his choice of 20/11/2026). Null for a work with no chapter.
     */
    fun of(chapters: List<ChapterReading>, resumeId: String?): ContinueTarget? {
        val stopped = chapters.firstOrNull { it.id == resumeId }
        val rereading = stopped?.progress?.let { progress ->
            val mark = progress.readMark
            val place = progress.position
            mark != null && place != null && place.at.isAfter(mark.at) && !atEnd(place)
        } == true
        val stoppedInRead = stopped?.progress?.readMark != null && !rereading
        val markedOnly = (stopped == null && chapters.any { it.progress.readMark != null }) || stoppedInRead
        val target = (if (markedOnly) chapters.firstOrNull { it.progress.readMark == null } ?: chapters.lastOrNull() else null)
            ?: stopped ?: chapters.firstOrNull() ?: return null
        return ContinueTarget(target.id, goesOn = stopped != null || markedOnly)
    }

    // The end-of-chapter block of an image chapter, or the end of a book's text.
    private fun atEnd(position: ReadingPosition): Boolean = when (position) {
        is StripPosition -> position.page >= position.totalPages
        is TextPosition -> position.fraction.value >= BasisPoints.END
    }
}
