package app.kamayoq.core.library

/** How the publication of a work stands at its source (FEATURES, part 3; progress contract, 2c). */
enum class PublicationStatus { ONGOING, HIATUS, COMPLETED, CANCELLED }

/**
 * [status] null is unknown. [missing] is how many chapters the source says are still to come or
 * absent, null when unknown: unknown never counts as zero (progress contract, 2c).
 */
data class Publication(val status: PublicationStatus?, val missing: Int?) {
    companion object {
        val UNKNOWN = Publication(status = null, missing = null)
    }
}

/** What a source says about a work, for the Work screen. A local work has none of it yet. */
data class WorkDetails(
    val authors: List<String>,
    val summary: String,
    val tags: List<String>,
    val publication: Publication,
) {
    companion object {
        val NONE = WorkDetails(authors = emptyList(), summary = "", tags = emptyList(), publication = Publication.UNKNOWN)
    }
}
