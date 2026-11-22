package app.kamayoq.core.reader

/** The four reading fonts in the APK (decision Q3 of 19/11/2026). */
enum class ReadingFont(val wire: String) {
    LITERATA("literata"),
    GARAMOND("garamond"),
    PLEX("plex"),
    ATKINSON("atkinson"),
}

/** The page's own theme; the bars and sheets follow the app's (DESIGN, 07/10/2026). */
enum class PageTheme(val wire: String) {
    AUTO("auto"),
    DARK("dark"),
    BLACK("black"),
    SEPIA("sepia"),
    LIGHT("light"),
}

/** What the footer on the page shows. */
enum class FooterContent(val wire: String) {
    ALL("all"),
    PROGRESS("progress"),
    NOTHING("nothing"),
}

/** Everything the settings panel edits, apart from the mode, which belongs to each work. */
enum class TextProperty { FONT, SIZE, LINE_HEIGHT, PARAGRAPH_SPACING, MARGIN, INDENT, JUSTIFY, THEME, FOOTER }

/** The panel's sliders. Line height and paragraph spacing go in tenths, so a step never drifts. */
enum class TextNumber(val property: TextProperty, val min: Int, val max: Int, val step: Int, val default: Int) {
    SIZE(TextProperty.SIZE, 14, 26, 1, 18),
    LINE_HEIGHT(TextProperty.LINE_HEIGHT, 12, 20, 1, 15),
    PARAGRAPH_SPACING(TextProperty.PARAGRAPH_SPACING, 0, 16, 2, 0),
    MARGIN(TextProperty.MARGIN, 12, 44, 4, 24),
}

/**
 * What one scope sets: null is not set there. The general scope's nulls are the defaults; a work's
 * nulls follow the general scope.
 */
data class TextLookChoices(
    val font: ReadingFont? = null,
    val size: Int? = null,
    val lineHeight: Int? = null,
    val paragraphSpacing: Int? = null,
    val margin: Int? = null,
    val indent: Boolean? = null,
    val justify: Boolean? = null,
    val theme: PageTheme? = null,
    val footer: FooterContent? = null,
) {
    /** The properties this scope sets, in the panel's order. */
    val properties: List<TextProperty>
        get() = emptyList()

    companion object {
        val NONE = TextLookChoices()
    }
}

/**
 * The look a page shows. [chosen] are the properties the person set in either scope: only those win
 * over the book's own styles (PERSONALITY, principle 7); the rest are defaults under the book's CSS.
 */
data class TextLook(
    val font: ReadingFont,
    val size: Int,
    val lineHeight: Int,
    val paragraphSpacing: Int,
    val margin: Int,
    val indent: Boolean,
    val justify: Boolean,
    val theme: PageTheme,
    val footer: FooterContent,
    val chosen: Set<TextProperty>,
) {
    companion object {
        /** Literata until he picks the default font on the S24 with real passages (decision Q4). */
        val DEFAULT = TextLook(
            font = ReadingFont.LITERATA,
            size = TextNumber.SIZE.default,
            lineHeight = TextNumber.LINE_HEIGHT.default,
            paragraphSpacing = TextNumber.PARAGRAPH_SPACING.default,
            margin = TextNumber.MARGIN.default,
            indent = true,
            justify = false,
            theme = PageTheme.AUTO,
            footer = FooterContent.ALL,
            chosen = emptySet(),
        )
    }
}

/** Which set the panel edits: "Vale para". Switching only changes that, never copies values. */
enum class TextScope { THIS_WORK, ALL_TEXT_WORKS }

/** One change made in the panel. */
sealed interface TextEdit {
    data class Font(val font: ReadingFont) : TextEdit
    data class Number(val number: TextNumber, val value: Int) : TextEdit
    data class Step(val number: TextNumber, val up: Boolean) : TextEdit
    data class Indent(val on: Boolean) : TextEdit

    /** "Justify and hyphenate": one control since 10/10/2026, as in a printed book. */
    data class Justify(val on: Boolean) : TextEdit
    data class Theme(val theme: PageTheme) : TextEdit
    data class Footer(val footer: FooterContent) : TextEdit
}

/** What a restore did, for the panel to say it. */
enum class TextRestore { GENERAL_DEFAULTS, OWN_DISCARDED, ALREADY_GENERAL }

/** The general settings, for every text work, and [own], what this work sets apart from them (null: none). */
data class TextSettings(val general: TextLookChoices, val own: TextLookChoices?) {

    /** What the page shows: the work's own over the general, the defaults under both. */
    fun effective(): TextLook = TextLook.DEFAULT

    /** The values the panel shows for [scope]: in "all", the general ones, without this work's. */
    fun shown(scope: TextScope): TextLook = TextLook.DEFAULT

    fun change(scope: TextScope, edit: TextEdit): TextSettings = this

    /**
     * In "all", the general defaults come back and the work keeps its own; in "this work", the work
     * drops its own and uses the general ones. Neither touches the mode.
     */
    fun restore(scope: TextScope): Pair<TextSettings, TextRestore> = this to TextRestore.ALREADY_GENERAL

    companion object {
        val DEFAULT = TextSettings(TextLookChoices.NONE, null)
    }
}

/** A page theme's colors, as 0xAARRGGBB. */
data class PageColors(val background: Long, val text: Long, val secondary: Long)

object PageThemes {
    /** "Auto" follows the system's light or dark; "Dark" is the app's own dark background. */
    fun colors(theme: PageTheme, systemDark: Boolean): PageColors = PageColors(0xFF000000, 0xFF000000, 0xFF000000)
}
