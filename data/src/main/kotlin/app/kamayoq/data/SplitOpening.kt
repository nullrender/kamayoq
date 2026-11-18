package app.kamayoq.data

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.BookContents
import app.kamayoq.core.reader.ReaderChapters

/** How a file to split opens, by what its own table of contents says as it opens. */
sealed interface FileOpening

/**
 * What the reader needs to read a file across its chapters: the chapters of the file in the order of
 * its contents, the reading of each, the one the person opened, and what the page is told.
 */
class SplitOpening(
    val chapterIds: List<String>,
    val saved: List<ChapterProgress>,
    val opened: Int,
    val chapters: ReaderChapters,
) : FileOpening

/** The file carries no division: it is read whole, as the chapter [chapterId]. */
class WholeOpening(val chapterId: String) : FileOpening

/**
 * Prepares the opening of [chapterId], a chapter of a file whose table of contents, read from the
 * file as it opens, is [contents].
 */
fun LibraryStore.splitOpening(chapterId: String, contents: BookContents): FileOpening {
    val keys = contents.chapters.map { it.key }
    var asked = currentEdition(chapterId)
    // The reading of the folder may be from before the file changed: the file itself says its chapters.
    if (fileChapters(asked).map { it.tocKey }.filter { it.isNotEmpty() } != keys) asked = resplit(asked, contents.chapters)
    // The chapter that stands for the one asked for now: bringing the chapters in line may have
    // given its place to another.
    if (keys.isEmpty()) return WholeOpening(asked)
    val rows = fileChapters(asked)
    return SplitOpening(
        chapterIds = rows.map { it.id },
        saved = rows.map { progress(it.id) },
        opened = rows.indexOfFirst { it.id == asked }.coerceAtLeast(0),
        chapters = ReaderChapters(keys, contents.chapterOfItem),
    )
}
