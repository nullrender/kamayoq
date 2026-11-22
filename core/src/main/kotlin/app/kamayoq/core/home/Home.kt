package app.kamayoq.core.home

import app.kamayoq.core.progress.ReadingState
import java.time.Duration
import java.time.Instant

/**
 * A chapter that arrived after the zero mark of its source, at [arrivedAt]; [order] is its place in
 * the work's reading order, for the chapters that came in the same reading.
 */
data class ArrivedChapter(val id: String, val arrivedAt: Instant, val read: Boolean, val order: Int = 0)

/** A work the person already has, with the chapters that arrived in it and the last opening of its Work screen. */
data class ArrivalWork(val workId: String, val state: ReadingState?, val lastVisit: Instant?, val chapters: List<ArrivedChapter>)

/** A work in Arrived: its [count] new chapters, the [shown] ones newest first, and when the newest came. */
data class ArrivedGroup(val workId: String, val count: Int, val shown: List<ArrivedChapter>, val latest: Instant)

/**
 * A work and the moment its reading last moved, null when nothing of it was opened, and the last
 * read mark put on any of its chapters, null for none.
 */
data class ReadingWork(val workId: String, val state: ReadingState?, val lastReadAt: Instant?, val lastMarkedAt: Instant? = null)

/** A work and when it came into the Library after the zero mark, null for one from before it. */
data class AddedWork(val workId: String, val addedAt: Instant?, val lastVisit: Instant?)

/** How long ago something happened, as Home says it ("2 h ago"). */
sealed interface Elapsed {
    data object JustNow : Elapsed
    data class Minutes(val count: Long) : Elapsed
    data class Hours(val count: Long) : Elapsed
    data object Yesterday : Elapsed
    data class Days(val count: Long) : Elapsed
}

/**
 * The rules of Home (reader-design FEATURES, part 2): what is new, which works show in Continue
 * reading, Arrived and Added, and how the time since is said.
 */
object Home {

    /** At most this many chapters open under a work in Arrived (board SinteseInicio). */
    const val ARRIVED_SHOWN = 10

    /** The newest works in Added (his answer of 21/11/2026). */
    const val ADDED_SHOWN = 10

    /**
     * The card and the strip under it: four whole covers and the next one cut by the screen's edge,
     * so the strip says it scrolls (FEATURES, part 2).
     */
    const val CONTINUE_SHOWN = 6

    /** From here the card says where the person left, not how long ago (PERSONALITY, "Paciente"). */
    val LEFT_LONG_AGO: Duration = Duration.ofDays(14)

    /**
     * A chapter is new when it arrived after the last time the person opened the work's page and is
     * not read yet. Opening the page makes it an ordinary unread chapter (FEATURES, part 2).
     */
    fun isNew(arrivedAt: Instant?, lastVisit: Instant?, read: Boolean): Boolean =
        arrivedAt != null && !read && (lastVisit == null || arrivedAt.isAfter(lastVisit))

    /** The works with new chapters, the most recent arrival first; only Reading and Want to read have news. */
    fun arrived(works: List<ArrivalWork>): List<ArrivedGroup> =
        works.filter { it.state == ReadingState.READING || it.state == ReadingState.WANT_TO_READ }
            .mapNotNull { work ->
                // The chapters of one reading share their arrival: the later in the work is the newer.
                val fresh = work.chapters.filter { isNew(it.arrivedAt, work.lastVisit, it.read) }
                    .sortedWith(compareByDescending<ArrivedChapter> { it.arrivedAt }.thenByDescending { it.order })
                fresh.firstOrNull()?.let { newest -> ArrivedGroup(work.workId, fresh.size, fresh.take(ARRIVED_SHOWN), newest.arrivedAt) }
            }
            .sortedByDescending { it.latest }

    /** The works of Continue reading, the most recently read first. */
    fun continueReading(works: List<ReadingWork>): List<String> =
        works.filter { it.state == ReadingState.READING && it.lastReadAt != null }
            .sortedByDescending { it.lastReadAt }
            .take(CONTINUE_SHOWN)
            .map { it.workId }

    /** The works of Added, the newest first, each until its page is opened. */
    fun added(works: List<AddedWork>): List<String> =
        works.mapNotNull { work -> work.addedAt?.takeIf { at -> work.lastVisit == null || work.lastVisit.isBefore(at) }?.let { work to it } }
            .sortedByDescending { it.second }
            .take(ADDED_SHOWN)
            .map { it.first.workId }

    /** How long before [now] [since] was. */
    fun elapsed(since: Instant, now: Instant): Elapsed {
        // A time ahead of now comes from a clock set back: it was a moment ago, not in the future.
        val gone = Duration.between(since, now).takeIf { !it.isNegative } ?: Duration.ZERO
        return when {
            gone < Duration.ofMinutes(1) -> Elapsed.JustNow
            gone < Duration.ofHours(1) -> Elapsed.Minutes(gone.toMinutes())
            gone < Duration.ofDays(1) -> Elapsed.Hours(gone.toHours())
            gone < Duration.ofDays(2) -> Elapsed.Yesterday
            else -> Elapsed.Days(gone.toDays())
        }
    }

    /** True when the last reading of a work was long enough ago to say where the person left it. */
    fun leftLongAgo(lastReadAt: Instant, now: Instant): Boolean = Duration.between(lastReadAt, now) >= LEFT_LONG_AGO
}
