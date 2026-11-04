package app.kamayoq.core.reader

import app.kamayoq.core.progress.BasisPoints
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the reader's page may tell the app. Everything else is refused, with a reason to log, so a
 * refusal can be told from a message that never came.
 */
sealed interface ReaderMessage {
    data object Ready : ReaderMessage
    data class Relocated(
        val section: Int,
        val sections: Int,
        val cfi: String,
        val position: BasisPoints,
        val reason: RelocateReason,
    ) : ReaderMessage

    /**
     * The fork has shown the saved position, or the start (piece 14). It may still re-anchor there
     * while it fills the screen around the page, always with [RelocateReason.ANCHOR].
     */
    data object Restored : ReaderMessage
    data class Failed(val code: String) : ReaderMessage

    /** A link the person touched; the page never opens it, and [ReaderLink] decides what the app does. */
    data class Link(val href: String) : ReaderMessage
    data class Refused(val reason: String) : ReaderMessage

    companion object {
        const val MAX_MESSAGE_LENGTH = 8 * 1024
        const val MAX_CFI_LENGTH = 2 * 1024
        const val MAX_LINK_LENGTH = 2 * 1024

        private val failureCode = Regex("[a-z-]{1,64}")

        fun parse(raw: String): ReaderMessage {
            if (raw.length > MAX_MESSAGE_LENGTH) return Refused("too long")
            val message = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return Refused("not a JSON object")
            return when (message.string("type")) {
                "ready" -> if (message.keys == setOf("type")) Ready else Refused("unexpected fields")
                "restored" -> if (message.keys == setOf("type")) Restored else Refused("unexpected fields")
                "relocated" -> relocated(message)
                "failed" -> failed(message)
                "link" -> link(message)
                else -> Refused("unknown type")
            }
        }

        private fun relocated(message: JsonObject): ReaderMessage {
            if (message.keys != setOf("type", "section", "sections", "cfi", "fraction", "reason")) return Refused("unexpected fields")
            val section = message.int("section")
            val sections = message.int("sections")
            if (section == null || sections == null || section !in 0 until sections) return Refused("section outside the book")
            val cfi = message.string("cfi")
            if (cfi == null || cfi.length > MAX_CFI_LENGTH || !cfi.startsWith("epubcfi(")) return Refused("bad cfi")
            val fraction = (message["fraction"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val position = BasisPoints.fromFraction(fraction) ?: return Refused("bad fraction")
            val reason = RelocateReason.entries.find { it.wire == message.string("reason") } ?: return Refused("unknown reason")
            return Relocated(section, sections, cfi, position, reason)
        }

        private fun link(message: JsonObject): ReaderMessage {
            val href = message.string("href")
            if (message.keys != setOf("type", "href") || href == null || href.length > MAX_LINK_LENGTH) return Refused("bad link")
            return Link(href)
        }

        private fun failed(message: JsonObject): ReaderMessage {
            val code = message.string("code")
            if (message.keys != setOf("type", "code") || code == null || !failureCode.matches(code)) return Refused("bad failure code")
            return Failed(code)
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    }
}

/**
 * Why the fork's paginator moved, as it names it. Only the person's moves may change a saved
 * position: the others re-lay the same place out after a new font, size or flow.
 */
enum class RelocateReason(val wire: String, val byPerson: Boolean) {
    PAGE("page", true),
    SNAP("snap", true),
    SCROLL("scroll", true),
    NAVIGATION("navigation", true),
    SELECTION("selection", true),
    ANCHOR("anchor", false),
    CONTAINER_SCROLL("container-scroll", false),
}
