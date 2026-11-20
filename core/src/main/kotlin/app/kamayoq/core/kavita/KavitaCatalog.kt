package app.kamayoq.core.kavita

data class RemoteLibrary(val id: Int, val name: String, val type: Int)

/** [coverImage] is the name Kavita gives the series' cover file, empty with no cover; it changes with the cover. */
data class RemoteWork(val id: Int, val name: String, val libraryId: Int, val coverImage: String = "")

/**
 * A series' metadata as Kavita gives it (KavitaDetailsFactsTest). [status] is Kavita's
 * PublicationStatus number; [maxCount] is -100000 when the series has no count.
 */
data class RemoteDetails(
    val summary: String,
    val writers: List<String>,
    val genres: List<String>,
    val tags: List<String>,
    val status: Int,
    val statusLocked: Boolean,
    val totalCount: Int,
    val maxCount: Int,
)

/** One file of a chapter. [path] is absolute in the server; [kind] is Kavita's MangaFormat. */
data class RemoteFile(val path: String, val kind: Int, val bytes: Long)

/** [minNumber] and [maxNumber] differ in a range; Kavita's own `number` keeps only the start. */
data class RemoteChapter(
    val id: Int,
    val minNumber: Double,
    val maxNumber: Double,
    val pages: Int,
    val volumeId: Int,
    val volumeNumber: Double,
    val isSpecial: Boolean,
    val titleName: String,
    val files: List<RemoteFile>,
    val pagesRead: Int = 0,
)

/** A chapter's record in the server. [at] is its own text, null when there is no record. */
/** [marker] is the book's `bookScrollId`, null when Kavita has none. */
data class RemoteProgress(val page: Int, val at: String?, val marker: String? = null)
