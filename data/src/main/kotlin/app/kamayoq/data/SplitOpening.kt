package app.kamayoq.data

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.BookContents
import app.kamayoq.core.reader.ReaderChapters

/**
 * What the reader needs to read a file across its chapters: the chapters of the file in the order of
 * its contents, the reading of each, the one the person opened, and what the page is told.
 */
class SplitOpening(
    val chapterIds: List<String>,
    val saved: List<ChapterProgress>,
    val opened: Int,
    val chapters: ReaderChapters,
)

/**
 * Prepares the opening of [chapterId], a chapter of a file whose table of contents, read from the
 * file as it opens, is [contents]. Null when the file is to be read whole.
 */
fun LibraryStore.splitOpening(chapterId: String, contents: BookContents): SplitOpening? {
    val keys = contents.chapters.map { it.key }
    var asked = currentEdition(chapterId)
    // The reading of the folder may be from before the file changed: the file itself says its chapters.
    if (fileChapters(asked).map { it.tocKey }.filter { it.isNotEmpty() } != keys) asked = resplit(asked, contents.chapters)
    if (keys.isEmpty()) return null
    val rows = fileChapters(asked)
    return SplitOpening(
        chapterIds = rows.map { it.id },
        saved = rows.map { progress(it.id) },
        opened = rows.indexOfFirst { it.id == asked }.coerceAtLeast(0),
        chapters = ReaderChapters(keys, contents.chapterOfItem),
    )
}
