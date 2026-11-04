package app.kamayoq.data

import app.kamayoq.core.progress.ChapterProgress

/**
 * Saves the reader's progress without letting a storage failure close the reader: the failure
 * comes back to be told and logged, the reading goes on, and the next change of image tries again.
 */
class ProgressSaving(private val store: LibraryStore) {
    /** Returns the failure, or null when the progress was saved. */
    fun save(chapterId: String, progress: ChapterProgress): Exception? =
        try {
            store.saveProgress(chapterId, progress)
            null
        } catch (e: Exception) {
            e
        }
}
