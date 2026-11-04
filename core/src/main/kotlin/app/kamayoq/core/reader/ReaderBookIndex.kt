package app.kamayoq.core.reader

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the page learns about the open book. The position travels here, never inside a script. */
object ReaderBookIndex {
    fun json(entries: Map<String, Long>, position: String?, flow: ReaderFlow): String = buildJsonObject {
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
    }.toString()
}
