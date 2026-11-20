package app.kamayoq.core.progress

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
    ): ReadingState? = null
}
