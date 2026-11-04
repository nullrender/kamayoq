package app.kamayoq.core.kavita

data class RemoteLibrary(val id: Int, val name: String, val type: Int)

data class RemoteWork(val id: Int, val name: String, val libraryId: Int)

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
data class RemoteProgress(val page: Int, val at: String?)
