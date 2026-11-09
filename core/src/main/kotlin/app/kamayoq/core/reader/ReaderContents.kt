package app.kamayoq.core.reader

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A native contents button refers to an index, never JavaScript made from a book's href. */
data class ReaderContentsItem(val index: Int, val title: String, val depth: Int)

object ReaderContents {
    const val MAX_ITEMS = 5_000
    const val MAX_DEPTH = 16
    const val MAX_TITLE = 512
    const val MAX_JSON = 4 * 1024 * 1024

    /** Null means the page did not provide a valid bounded list; an empty list means no contents. */
    fun parse(raw: String): List<ReaderContentsItem>? {
        if (raw.length > MAX_JSON) return null
        val list = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: return null
        if (list.size > MAX_ITEMS) return null
        return list.mapIndexed { position, element ->
            val item = element as? JsonObject ?: return null
            if (item.keys != setOf("index", "title", "depth")) return null
            val index = (item["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val depth = (item["depth"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val title = (item["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (index != position || depth == null || depth !in 0..MAX_DEPTH || title == null || title.length > MAX_TITLE) return null
            ReaderContentsItem(position, title, depth)
        }
    }
}
