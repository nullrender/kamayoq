package app.kamayoq.core.reader

import app.kamayoq.core.kavita.KavitaText
import app.kamayoq.core.progress.BasisPoints
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
        /** Kavita's marker of the element the page starts at, taken on the rendered section (milestone C2). */
        val marker: String? = null,
        /**
         * In a file split by its table of contents, the chapter the page is in, counted from 0 in the
         * chapters the app handed the page, and how far into that chapter the page goes; the second
         * is null when the page cannot measure it (milestone B7).
         */
        val chapter: Int? = null,
        val chapterPosition: BasisPoints? = null,
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

    /** The person asked for the next page on the book's last one (piece 23). */
    data object End : ReaderMessage

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
                "end" -> if (message.keys == setOf("type")) End else Refused("unexpected fields")
                else -> Refused("unknown type")
            }
        }

        private fun relocated(message: JsonObject): ReaderMessage {
            val fields = setOf("type", "section", "sections", "cfi", "fraction", "reason")
            val optional = setOf("marker", "chapter", "chapterFraction")
            if (!message.keys.containsAll(fields) || !(fields + optional).containsAll(message.keys)) return Refused("unexpected fields")
            val section = message.int("section")
            val sections = message.int("sections")
            if (section == null || sections == null || section !in 0 until sections) return Refused("section outside the book")
            val cfi = message.string("cfi")
            if (cfi == null || cfi.length > MAX_CFI_LENGTH || !cfi.startsWith("epubcfi(")) return Refused("bad cfi")
            val fraction = (message["fraction"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val position = BasisPoints.fromFraction(fraction) ?: return Refused("bad fraction")
            val reason = RelocateReason.entries.find { it.wire == message.string("reason") } ?: return Refused("unknown reason")
            // A marker the conversion cannot send is no marker: the place itself still counts.
            val marker = when (val raw = message["marker"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (raw.isString) KavitaText.markerOrNull(raw.content) else return Refused("bad marker")
                else -> return Refused("bad marker")
            }
            return Relocated(section, sections, cfi, position, reason, marker)
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
    CONTAINER_SCROLL("container-scroll", false);

    /**
     * Reading on, page after page or by scrolling: what takes the person from a chapter into the
     * next. A jump by the contents or by a link moves without reading what is in between.
     */
    val turns: Boolean get() = this == PAGE || this == SNAP || this == SCROLL
}
