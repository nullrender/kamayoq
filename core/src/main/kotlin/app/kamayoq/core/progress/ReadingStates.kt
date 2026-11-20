package app.kamayoq.core.progress

import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.WorkType

/**
 * Where the person stands with a work, apart from the progress of its chapters (reader-design
 * FEATURES, part 3, "Estado de leitura"). A work has one of these or none, which is null.
 */
enum class ReadingState { READING, WANT_TO_READ, ON_HOLD, DROPPED, COMPLETED }

/**
 * Where a book's reading was when its state was last decided, by the person or by the app: how far
 * into [chapterId] it was. The automatic changes of a book count from here, so a change undone does
 * not come back at the next page.
 */
data class StateMark(val chapterId: String, val fraction: BasisPoints)

/** The automatic changes of a work's state that reading makes (FEATURES, part 3). */
object ReadingStates {

    /**
     * The state the work moves to by itself when [chapterId]'s progress goes from [before] to
     * [after], or null when it stays as [state].
     */
    fun afterSave(
        state: ReadingState?,
        type: WorkType,
        chapterId: String,
        before: ChapterProgress,
        after: ChapterProgress,
        mark: StateMark?,
    ): ReadingState? {
        val threshold = when (state) {
            null, ReadingState.WANT_TO_READ -> BOOK_START
            ReadingState.ON_HOLD -> BOOK_RESUME
            // Only the person moves a work out of these.
            ReadingState.READING, ReadingState.DROPPED, ReadingState.COMPLETED -> return null
        }
        val advanced = if (type == WorkType.BOOKS) {
            bookAdvanced(chapterId, after, mark, threshold)
        } else {
            chapterEnded(before, after)
        }
        return ReadingState.READING.takeIf { advanced }
    }

    /**
     * Completed, when an event finishes the work: [finishedLast] says the event finished the last
     * chapter there is (reading it to its end, or marking it read), and [everyChapterRead] that with
     * it every chapter is read. Null when the state stays.
     */
    fun afterFinishing(
        state: ReadingState?,
        type: WorkType,
        publication: Publication,
        finishedLast: Boolean,
        everyChapterRead: Boolean,
    ): ReadingState? {
        // A finished book stays as it is: a book in volumes in the folder may be incomplete with no
        // way for the app to know (his decision, 20/11/2026). Missing unknown never counts as zero.
        val finished = type != WorkType.BOOKS && state != ReadingState.COMPLETED &&
            publication.status == PublicationStatus.COMPLETED && publication.missing == 0 &&
            finishedLast && everyChapterRead
        return ReadingState.COMPLETED.takeIf { finished }
    }

    // A book is one long reading unit: a chapter's end would come too late, so it counts how far in.
    private fun bookAdvanced(chapterId: String, after: ChapterProgress, mark: StateMark?, threshold: Int): Boolean {
        val fraction = (after.position as? TextPosition)?.fraction?.value ?: return false
        // A mark in another volume says nothing about this one: here the reading counts from its start.
        val from = mark?.takeIf { it.chapterId == chapterId }?.fraction?.value ?: BasisPoints.START
        return fraction - from >= threshold
    }

    // Reading to the end counts one more complete read; a mark by hand counts none, and is not reading.
    private fun chapterEnded(before: ChapterProgress, after: ChapterProgress): Boolean =
        after.completeReads > before.completeReads

    /**
     * 2 % to start and 1 % to resume, in basis points: the hypothesis of FEATURES, part 3, to compare
     * on the phone. A book's other hypothesis, the end of the first chapter of its contents, needs a
     * table of contents the app does not keep for a book.
     */
    private const val BOOK_START = 200
    private const val BOOK_RESUME = 100
}
