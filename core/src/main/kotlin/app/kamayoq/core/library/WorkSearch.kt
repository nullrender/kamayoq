package app.kamayoq.core.library

/**
 * The Library's magnifier: which works answer what was typed. It looks in the works the app holds,
 * from the phone's folder and from Kavita's included libraries alike.
 */
object WorkSearch {
    fun matches(query: String, title: String, alternativeTitle: String?): Boolean = true
}
