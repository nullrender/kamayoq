package app.kamayoq.core.reader

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the page learns about the open book. The position travels here, never inside a script. */
object ReaderBookIndex {
    /**
     * [section] is where the saved place was, for a [position] the book no longer resolves. A place
     * from Kavita comes as its [section] and [marker], with the number of [sections] Kavita counts:
     * a book that has another number opens at its start.
     */
    fun json(
        entries: Map<String, Long>,
        position: String?,
        flow: ReaderFlow,
        section: Int? = null,
        marker: String? = null,
        sections: Int? = null,
    ): String = buildJsonObject {
        putJsonArray("entries") {
            for ((name, size) in entries) {
                addJsonObject {
                    put("name", name)
                    put("size", size)
                }
            }
        }
        put("position", position)
        put("flow", flow.wire)
        section?.let { put("section", it) }
        marker?.let { put("marker", it) }
        sections?.let { put("sections", it) }
    }.toString()
}
