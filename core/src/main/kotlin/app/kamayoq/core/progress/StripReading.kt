package app.kamayoq.core.progress

import java.time.Instant

/**
 * Where the person is in a chapter read in the Strip (progress contract, section 9). [page] counts
 * images from 0; [page] equal to [totalPages] is the end-of-chapter block after the last image.
 */
data class StripPosition(val page: Int, val totalPages: Int, val at: Instant, val device: String) {
    init {
        require(totalPages >= 1) { "A chapter in the Strip has at least one image: $totalPages" }
        require(page in 0..totalPages) { "Page $page is outside 0..$totalPages" }
    }

    val isAtEnd: Boolean get() = page == totalPages
}

/** The device of a position that came from the server. */
object KavitaDevice {
    const val NAME = "kavita"
}

/** One opening of a chapter in the Strip, from the saved progress to each image shown. */
class StripReading private constructor(saved: ChapterProgress, val totalPages: Int, private val device: String) {

    /**
     * Where the chapter opens (section 9): where it stopped, unless the last reading reached the
     * end, or the file now has another number of images.
     */
    val startPage: Int = saved.position
        ?.takeIf { (it.totalPages == totalPages || it.device == KavitaDevice.NAME) && !it.isAtEnd }
        // Kavita may count pages differently (hidden folders, for one): its page still says where.
        ?.page
        ?.coerceAtMost(totalPages - 1)
        ?: 0

    var progress: ChapterProgress = saved
        private set

    // Lives with the opening, not in the saved progress: a new opening may count a new complete read.
    private var endCounted = false

    /**
     * Records that [page] is on screen and returns the progress to save. A page outside the
     * chapter is refused (null) and changes nothing.
     */
    fun show(page: Int, at: Instant): ChapterProgress? {
        if (page < 0 || page > totalPages) return null
        val current = progress.position
        val samePlace = current != null && current.page == page && current.totalPages == totalPages
        // The end block stays news until this opening counts it, even if the last opening stopped there.
        if (samePlace && (page != totalPages || endCounted)) return progress

        val position = StripPosition(page, totalPages, at, device)
        progress = if (position.isAtEnd && !endCounted) {
            endCounted = true
            progress.copy(
                readMark = progress.readMark ?: ReadMark(at, ReadMarkSource.READ_TO_END),
                position = position,
                completeReads = progress.completeReads + 1,
            )
        } else {
            progress.copy(position = position)
        }
        return progress
    }

    companion object {
        /** A chapter without images cannot be read in the Strip, so it is refused (null). */
        fun open(saved: ChapterProgress, totalPages: Int, device: String): StripReading? =
            if (totalPages < 1) null else StripReading(saved, totalPages, device)
    }
}
