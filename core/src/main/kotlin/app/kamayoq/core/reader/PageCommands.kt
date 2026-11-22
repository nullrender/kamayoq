package app.kamayoq.core.reader

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Commands posted to the reader's page that carry text: built as JSON, so the text stays data. */
object PageCommands {
    /** Go to a place the app kept, by its CFI. */
    fun place(cfi: String): String = buildJsonObject {
        put("type", "place")
        put("cfi", cfi)
    }.toString()
}
