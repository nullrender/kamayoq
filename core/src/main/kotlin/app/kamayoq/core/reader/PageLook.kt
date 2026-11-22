package app.kamayoq.core.reader

import kotlinx.serialization.json.JsonObject

/** The look a page shows, with its theme's colors already resolved for the system's light or dark. */
data class ShownLook(val look: TextLook, val colors: PageColors)

/**
 * The look as the reader's page takes it, in the book's index and in the command that changes it
 * with the book open. Only numbers, fixed names and colors from [PageThemes] travel: nothing here
 * comes from the book, and the page checks each value again before it writes any CSS.
 */
object PageLook {
    fun json(shown: ShownLook): JsonObject = JsonObject(emptyMap())

    /** The command the page applies with the book open, posted as a message, never evaluated. */
    fun command(shown: ShownLook): String = """{"type":"look"}"""
}
