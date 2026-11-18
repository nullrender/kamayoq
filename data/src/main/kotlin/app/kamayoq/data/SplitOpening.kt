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
fun LibraryStore.splitOpening(chapterId: String, contents: BookContents): SplitOpening? = null
