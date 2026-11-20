package app.kamayoq.core.library

import java.text.Normalizer

/**
 * The Library's magnifier: which works answer what was typed. It looks in the works the app holds,
 * from the phone's folder and from Kavita's included libraries alike.
 */
object WorkSearch {
    private val marks = Regex("""\p{Mn}+""")
    private val spaces = Regex("""\s+""")

    fun matches(query: String, title: String, alternativeTitle: String?): Boolean {
        val wanted = fold(query)
        if (wanted.isEmpty()) return true
        return fold(title).contains(wanted) || (alternativeTitle != null && fold(alternativeTitle).contains(wanted))
    }

    // Typed on a phone keyboard, a title often loses its accents and capitals.
    private fun fold(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().trim().replace(spaces, " ")
}
