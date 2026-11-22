package app.kamayoq.core.reader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The look a page shows, with its theme's colors already resolved for the system's light or dark. */
data class ShownLook(val look: TextLook, val colors: PageColors)

/**
 * The look as the reader's page takes it, in the book's index and in the command that changes it
 * with the book open. Only numbers, fixed names and colors from [PageThemes] travel: nothing here
 * comes from the book, and the page checks each value again before it writes any CSS.
 */
object PageLook {
    fun json(shown: ShownLook): JsonObject = with(shown.look) {
        buildJsonObject {
            put("font", font.wire)
            put("size", size)
            put("lineHeight", lineHeight)
            put("paragraphSpacing", paragraphSpacing)
            put("margin", margin)
            put("indent", indent)
            put("justify", justify)
            put("background", hex(shown.colors.background))
            put("text", hex(shown.colors.text))
            // In the panel's order, so the same look always gives the same text.
            putJsonArray("chosen") { for (property in TextProperty.entries) if (property in chosen) add(WIRE.getValue(property)) }
        }
    }

    /** The command the page applies with the book open, posted as a message, never evaluated. */
    fun command(shown: ShownLook): String = buildJsonObject {
        put("type", "look")
        put("look", json(shown))
    }.toString()

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    private val WIRE = mapOf(
        TextProperty.FONT to "font",
        TextProperty.SIZE to "size",
        TextProperty.LINE_HEIGHT to "lineHeight",
        TextProperty.PARAGRAPH_SPACING to "paragraphSpacing",
        TextProperty.MARGIN to "margin",
        TextProperty.INDENT to "indent",
        TextProperty.JUSTIFY to "justify",
        TextProperty.THEME to "theme",
        TextProperty.FOOTER to "footer",
    )
}
