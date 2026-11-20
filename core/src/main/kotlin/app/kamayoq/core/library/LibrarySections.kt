package app.kamayoq.core.library

import java.text.Normalizer

/** One section of the Library: the works of one type, in the order they are listed. */
data class LibrarySection<T>(val type: WorkType, val works: List<T>)

/**
 * The Library grouped by type, its default grouping (reader-design, FEATURES.md, Part 2): Quadrinhos,
 * Novels and Livros, in this order; a type with no work has no section; inside a section, works go
 * by title, without a leading article of Portuguese or English (24/10/2026).
 */
object LibrarySections {
    private val order = listOf(WorkType.COMICS, WorkType.NOVELS, WorkType.BOOKS)

    // Phase 1 drops only the articles of Portuguese and English (decided on 24/10/2026).
    private val article = Regex("""^(o|a|os|as|um|uma|uns|umas|the|an)\s+(?=\S)""", RegexOption.IGNORE_CASE)
    private val marks = Regex("""\p{Mn}+""")

    fun <T> of(works: List<T>, type: (T) -> WorkType, title: (T) -> String): List<LibrarySection<T>> {
        val byTitle = compareBy<T, String>(NaturalOrder) { sortKey(title(it)) }.thenBy(NaturalOrder) { title(it) }
        return order.mapNotNull { kind ->
            works.filter { type(it) == kind }.takeIf { it.isNotEmpty() }?.let { LibrarySection(kind, it.sortedWith(byTitle)) }
        }
    }

    /** The title as it is compared: no leading article, no accents ("Ícaro" goes with the I). */
    internal fun sortKey(title: String): String {
        val trimmed = title.trim().replaceFirst(article, "")
        return Normalizer.normalize(trimmed, Normalizer.Form.NFD).replace(marks, "")
    }
}
