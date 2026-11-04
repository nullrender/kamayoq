package app.kamayoq.core.library

/** Type of a work (folder convention, rule 1). */
enum class WorkType { COMICS, NOVELS, BOOKS }

/** Optional subtype of comics, shown as a badge; it comes from metadata, never from a folder (27/10/2026). */
enum class ComicsSubtype { MANGA, MANHWA, WEBTOON }

/** "Vol." or "Volume" in the name makes a unit a volume (rule 4); everything else is a chapter. */
enum class UnitKind { CHAPTER, VOLUME }

/** Where the images of a reading unit are. */
sealed interface UnitContent {
    /** A CBZ or ZIP file; its pages are listed from inside the file when it opens. */
    data object Archive : UnitContent

    /** A folder of images, with the pages in reading order. */
    data class ImageFolder(val pages: List<String>) : UnitContent
}

/**
 * How a stored chapter is kept, without its pages: what [UnitContent] leaves once the folder is read.
 * The name tells the content, never the origin. [OTHER_ARCHIVE] is a CBR and the like, which no
 * reader opens yet; only Kavita brings it and [EPUB] and [PDF] for now.
 */
enum class ChapterFormat { ARCHIVE, IMAGE_FOLDER, EPUB, PDF, OTHER_ARCHIVE }

/** One reading unit (rule 3): a file or a folder of images. */
data class ReadingUnit(
    val path: String,
    /** The file or folder name without the extension, as the list shows it. */
    val name: String,
    val kind: UnitKind,
    /** Unknown is null, never zero: unnumbered units go after the numbered ones (rule E3). */
    val number: Double?,
    val content: UnitContent,
)

data class Work(
    /** The work folder, or the file itself for a loose file (a work of a single volume). */
    val path: String,
    val title: String,
    /** The second title, from the end of the folder name in parentheses (rule 2). */
    val alternativeTitle: String?,
    val type: WorkType,
    val subtype: ComicsSubtype?,
    /** In reading order (rule E3). */
    val units: List<ReadingUnit>,
)

/** Why a file was left out. Nothing is left out silently, except the app's own and hidden files. */
enum class IgnoreReason {
    /** A format this version does not read yet (only CBZ, ZIP and folders of images for now). */
    UNSUPPORTED_FORMAT,

    /** A file where the convention expects none, such as an image loose in a type folder. */
    UNEXPECTED_PLACE,
}

data class IgnoredFile(val path: String, val reason: IgnoreReason)

data class LocalLibrary(val works: List<Work>, val ignored: List<IgnoredFile>)
