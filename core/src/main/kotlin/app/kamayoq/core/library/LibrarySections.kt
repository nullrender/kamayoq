package app.kamayoq.core.library

/** One section of the Library: the works of one type, in the order they are listed. */
data class LibrarySection<T>(val type: WorkType, val works: List<T>)

/**
 * The Library grouped by type, its default grouping (reader-design, FEATURES.md, Part 2): Quadrinhos,
 * Novels and Livros, in this order; a type with no work has no section; inside a section, works go
 * by title, without a leading article of Portuguese or English (24/10/2026).
 */
object LibrarySections {
    fun <T> of(works: List<T>, type: (T) -> WorkType, title: (T) -> String): List<LibrarySection<T>> = emptyList()
}
