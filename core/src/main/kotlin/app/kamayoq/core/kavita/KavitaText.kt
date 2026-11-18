package app.kamayoq.core.kavita

import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.KavitaDevice
import app.kamayoq.core.progress.TextPosition
import java.time.Instant

/**
 * A book's place as Kavita keeps it: [page] is the spine index, or the number of spine items for a
 * book read to the end, and [marker] the `bookScrollId` inside that section, null when there is none.
 */
data class KavitaPlace(val page: Int, val marker: String?)

/** A place that came from Kavita, as this app reads it: [read] when the server says the book is. */
data class KavitaTextRemote(val position: TextPosition, val marker: String?, val read: Boolean)

/**
 * The place in a book going to Kavita and back (step 4, milestone C2). The section passes exactly;
 * the place inside it only as near as the element the marker names (progress contract, section 6).
 */
object KavitaText {
    const val MAX_MARKER_LENGTH = 1024

    // The two forms Kavita's web reader writes (reader.service.ts, getXPathTo and
    // descopeBookReaderXpath, at v0.9.1.4): a path of tag[n] steps from the section's body, or from
    // an element's id. Anything else, such as `idx:12` from another client, is not a place here.
    private const val STEP = "/[a-z][a-z0-9-]*\\[[1-9][0-9]{0,5}]"
    private val form = Regex("(//body|id\\(\"[^\"\\\\]{1,256}\"\\))($STEP)*")

    /** [raw] when it is a marker this app can find in a section; null opens the section's start. */
    fun markerOrNull(raw: String?): String? =
        raw?.takeIf { it.length <= MAX_MARKER_LENGTH && form.matches(it) }

    /**
     * What to send for [position], or null for nothing to send. A book that Kavita counts with a
     * different number of sections than the reader is another layout of the file: no section here
     * means the same there, so nothing goes. With no record in Kavita, a place in the first section
     * stays here (decision C-1): the server drops page 0 without a record. The end goes only when the
     * person reached it here, through the end block (decision C-3), which is what [read] says: never
     * the read mark alone, which stays through a reread. [marker] goes as it is: the reader's own are
     * already in a known form, and one agreed with Kavita must stay as the server has it.
     */
    fun toRemote(position: TextPosition?, read: Boolean, marker: String?, pageCount: Int, remoteHasRecord: Boolean): KavitaPlace? {
        if (position == null || position.sections != pageCount) return null
        if (read && position.section == position.sections - 1) return KavitaPlace(pageCount, marker)
        if (position.section == 0 && !remoteHasRecord) return null
        return KavitaPlace(position.section, marker)
    }

    /**
     * The place Kavita has, for a book of [pageCount] sections; null for a book with none. A page at
     * or past the total is the book read (decision C-2): it reopens at the marker in the last section.
     * The CFI only names the section's body, and the fraction is the section's share of the count,
     * an estimate until the reader reports the real one: the reader opens a Kavita place by its
     * section and marker, never by this CFI.
     */
    fun fromRemote(place: KavitaPlace, pageCount: Int, at: Instant): KavitaTextRemote? {
        if (pageCount < 1) return null
        val section = place.page.coerceIn(0, pageCount - 1)
        val fraction = BasisPoints.fromStored(section * BasisPoints.END / pageCount)
        val position = TextPosition(section, pageCount, sectionStart(section), fraction, at, KavitaDevice.NAME)
        return KavitaTextRemote(position, markerOrNull(place.marker), read = place.page >= pageCount)
    }

    /** A CFI at the body of spine item [section], in the usual package layout (spine third). */
    fun sectionStart(section: Int): String = "epubcfi(/6/${(section + 1) * 2}!/4)"

    /**
     * Compares both sides with the last agreement, as [KavitaProgress.decide] does with pages, now
     * with the marker as part of the content (D2). A change of marker only, inside the same section
     * on both sides, does not ask (decision C-4): Kavita's web reader rewrites the marker on opening,
     * without reading, and the place read here goes.
     */
    fun decide(local: KavitaPlace?, agreed: KavitaPlace?, remote: KavitaPlace): KavitaProgress.Decision {
        val base = agreed ?: KavitaPlace(0, null)
        val localChanged = local != null && local != base
        val remoteChanged = remote != base
        return when {
            !localChanged && !remoteChanged -> KavitaProgress.Decision.IN_SYNC
            local == remote -> KavitaProgress.Decision.AGREE
            !remoteChanged -> KavitaProgress.Decision.SEND
            !localChanged -> KavitaProgress.Decision.ADOPT
            checkNotNull(local).page == remote.page -> KavitaProgress.Decision.SEND
            else -> KavitaProgress.Decision.CONFLICT
        }
    }
}
