package app.kamayoq.data

import app.kamayoq.core.progress.ReadingPosition
import java.time.Instant

/**
 * A work of Continue reading: the chapter Continue opens ([target], [goesOn] as on the Work screen),
 * the place the reading stopped, and when it last moved.
 */
data class ContinueItem(
    val work: StoredWork,
    val target: StoredChapter,
    val goesOn: Boolean,
    val chapterCount: Int,
    val position: ReadingPosition?,
    val lastReadAt: Instant,
)

/** A chapter of Arrived and when it arrived. */
data class ArrivedRow(val chapter: StoredChapter, val arrivedAt: Instant)

/** A work of Arrived: how many new chapters, the ones shown newest first, and when the newest came. */
data class ArrivedItem(val work: StoredWork, val count: Int, val chapters: List<ArrivedRow>, val latest: Instant)

/** A work of Added, with its chapters counted and when it came in. */
data class AddedItem(val work: StoredWork, val chapterCount: Int, val addedAt: Instant)

/** What Home shows (reader-design FEATURES, part 2); a section with nothing in it is left out. */
data class HomeContent(val continueReading: List<ContinueItem>, val arrived: List<ArrivedItem>, val added: List<AddedItem>) {
    val isEmpty: Boolean get() = continueReading.isEmpty() && arrived.isEmpty() && added.isEmpty()
}

/** Home as the store has it now, by the rules of core Home. */
fun LibraryStore.home(): HomeContent = HomeContent(emptyList(), emptyList(), emptyList())
