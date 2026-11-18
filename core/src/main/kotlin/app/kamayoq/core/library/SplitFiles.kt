package app.kamayoq.core.library

import app.kamayoq.core.reader.ContentsChapter

/**
 * Turns each file to split into the chapters of its table of contents
 * (`docs/decisions/novel-chapters-2026-11-18.md`, section 2).
 */
object SplitFiles {
    /**
     * [chaptersOf] gives the chapters read from a file's table of contents, in its order. None means
     * the file does not carry the division: it stays one chapter, the whole file.
     */
    fun expand(library: LocalLibrary, chaptersOf: (ReadingUnit) -> List<ContentsChapter>): LocalLibrary =
        library.copy(
            works = library.works.map { work ->
                work.copy(
                    // The files keep their order (rule E3); inside a file, the order of its contents.
                    units = work.units.flatMap { unit ->
                        if (unit.content == UnitContent.SplitEpub) chapters(unit, chaptersOf(unit)) else listOf(unit)
                    },
                )
            },
        )

    private fun chapters(file: ReadingUnit, chapters: List<ContentsChapter>): List<ReadingUnit> {
        if (chapters.isEmpty()) return listOf(file)
        return chapters.map { chapter ->
            ReadingUnit(
                path = file.path,
                name = chapter.title.ifBlank { file.name },
                kind = UnitKind.CHAPTER,
                number = FolderConvention.chapterNumber(chapter.title),
                content = UnitContent.SplitEpub,
                tocKey = chapter.key,
            )
        }
    }
}
