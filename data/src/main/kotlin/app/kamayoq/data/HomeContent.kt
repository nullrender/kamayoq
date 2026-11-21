package app.kamayoq.data

import app.kamayoq.core.home.AddedWork
import app.kamayoq.core.home.ArrivalWork
import app.kamayoq.core.home.ArrivedChapter
import app.kamayoq.core.home.ChapterReading
import app.kamayoq.core.home.ContinueTargets
import app.kamayoq.core.home.Home
import app.kamayoq.core.home.ReadingWork
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

/**
 * Home as the store has it now, by the rules of core Home. Only works present in the Library: what
 * left the folder or an unmarked library leaves Home too.
 */
fun LibraryStore.home(): HomeContent {
    val works = works()
    val byId = works.associateBy { it.id }
    val chapters = works.associate { it.id to chapters(it.id) }
    val progress = chapters.values.flatten().associate { it.id to progress(it.id) }
    val states = works.associate { it.id to readingState(it.id).state }
    val visits = works.associate { it.id to lastVisit(it.id) }
    val lastRead = works.associate { work -> work.id to chapters.getValue(work.id).mapNotNull { progress.getValue(it.id).position?.at }.maxOrNull() }
    val continueReading = Home.continueReading(works.map { ReadingWork(it.id, states[it.id], lastRead[it.id]) }).mapNotNull { id ->
        val list = chapters.getValue(id)
        val resume = resumeChapter(id)?.takeIf { it.present }
        val target = ContinueTargets.of(list.map { ChapterReading(it.id, progress.getValue(it.id)) }, resume?.id) ?: return@mapNotNull null
        ContinueItem(
            work = byId.getValue(id),
            target = list.first { it.id == target.chapterId },
            goesOn = target.goesOn,
            chapterCount = list.size,
            position = progress.getValue(target.chapterId).position,
            lastReadAt = checkNotNull(lastRead[id]),
        )
    }
    val arrivalWorks = works.map { work ->
        ArrivalWork(
            work.id, states[work.id], visits[work.id],
            arrivals(work.id).map { (chapterId, at) -> ArrivedChapter(chapterId, at, progress[chapterId]?.readMark != null) },
        )
    }
    val arrived = Home.arrived(arrivalWorks).map { group ->
        val list = chapters.getValue(group.workId)
        ArrivedItem(
            byId.getValue(group.workId), group.count,
            group.shown.map { shown -> ArrivedRow(list.first { it.id == shown.id }, shown.arrivedAt) }, group.latest,
        )
    }
    val addedAt = works.associate { it.id to addedAt(it.id) }
    val added = Home.added(works.map { AddedWork(it.id, addedAt[it.id], visits[it.id]) }).map { id ->
        AddedItem(byId.getValue(id), chapters.getValue(id).size, checkNotNull(addedAt[id]))
    }
    return HomeContent(continueReading, arrived, added)
}
