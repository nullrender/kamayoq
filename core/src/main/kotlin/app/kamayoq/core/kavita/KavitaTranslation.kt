package app.kamayoq.core.kavita

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType

/** A Kavita series as the Library keeps it. [path] is the series id. */
data class ImportedWork(
    val path: String,
    val title: String,
    val type: WorkType,
    val subtype: ComicsSubtype?,
    val libraryId: Int,
    val chapters: List<ImportedChapter>,
)

/** A Kavita chapter as the Library keeps it, in reading order. [path] is the chapter id. */
data class ImportedChapter(
    val path: String,
    val name: String,
    val kind: UnitKind,
    val number: Double?,
    val format: ChapterFormat,
    val stamp: String,
    val pageCount: Int,
    val remoteFile: String?,
    val remoteVolumeId: Int,
)

/** What Kavita's answers mean here. Numbers measured in `KavitaReturnFactsTest`. */
object KavitaTranslation {
    private const val NO_NUMBER = -100000.0
    private const val SPECIALS = 100000.0
    private const val FILE_ARCHIVE = 1
    private const val FILE_EPUB = 3
    private const val FILE_PDF = 4

    private val readableArchives = setOf("cbz", "zip")

    /** Throws [IllegalArgumentException] for a library type that did not exist when this was written. */
    fun work(library: RemoteLibrary, work: RemoteWork, chapters: List<RemoteChapter>): ImportedWork {
        val (type, subtype) = typeOf(library.type)
        return ImportedWork(
            path = work.id.toString(),
            title = work.name,
            type = type,
            subtype = subtype,
            libraryId = library.id,
            // Stable: inside a volume, the server's order stands.
            chapters = chapters.sortedBy(::readingOrder).map(::chapter),
        )
    }

    // The server lists loose chapters first; its reader puts them after the volumes (ReaderService.cs:539).
    private fun readingOrder(chapter: RemoteChapter): Double = when (chapter.volumeNumber) {
        NO_NUMBER -> SPECIALS - 1
        else -> chapter.volumeNumber
    }

    private fun typeOf(libraryType: Int): Pair<WorkType, ComicsSubtype?> = when (libraryType) {
        0 -> WorkType.COMICS to ComicsSubtype.MANGA
        1, 3, 5 -> WorkType.COMICS to null
        2 -> WorkType.BOOKS to null
        4 -> WorkType.NOVELS to null
        else -> throw IllegalArgumentException("Library type $libraryType is unknown")
    }

    private fun chapter(chapter: RemoteChapter): ImportedChapter {
        val fileName = chapter.files.minOfOrNull { nameOf(it.path) }
        val number = chapter.minNumber.takeIf { it != NO_NUMBER }
        val inNumberedVolume = chapter.volumeNumber != NO_NUMBER && chapter.volumeNumber != SPECIALS
        return ImportedChapter(
            path = chapter.id.toString(),
            name = chapter.titleName.ifBlank { fileName?.substringBeforeLast('.').orEmpty() },
            kind = if (number == null && inNumberedVolume) UnitKind.VOLUME else UnitKind.CHAPTER,
            number = number,
            format = formatOf(chapter.files),
            // The server's dates change with a forced scan; pages and size only with the file.
            stamp = "${chapter.pages}|${chapter.files.sumOf { it.bytes }}",
            pageCount = chapter.pages,
            remoteFile = fileName,
            remoteVolumeId = chapter.volumeId,
        )
    }

    private fun formatOf(files: List<RemoteFile>): ChapterFormat {
        val file = files.singleOrNull() ?: return ChapterFormat.OTHER_ARCHIVE
        return when (file.kind) {
            FILE_EPUB -> ChapterFormat.EPUB
            FILE_PDF -> ChapterFormat.PDF
            FILE_ARCHIVE ->
                if (file.path.substringAfterLast('.').lowercase() in readableArchives) ChapterFormat.ARCHIVE
                else ChapterFormat.OTHER_ARCHIVE
            else -> ChapterFormat.OTHER_ARCHIVE
        }
    }

    // The path is the server's own, so a Windows server brings backslashes.
    private fun nameOf(path: String) = path.substringAfterLast('/').substringAfterLast('\\')
}
