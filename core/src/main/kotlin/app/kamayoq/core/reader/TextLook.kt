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
        get() = listOfNotNull(
            TextProperty.FONT.takeIf { font != null },
            TextProperty.SIZE.takeIf { size != null },
            TextProperty.LINE_HEIGHT.takeIf { lineHeight != null },
            TextProperty.PARAGRAPH_SPACING.takeIf { paragraphSpacing != null },
            TextProperty.MARGIN.takeIf { margin != null },
            TextProperty.INDENT.takeIf { indent != null },
            TextProperty.JUSTIFY.takeIf { justify != null },
            TextProperty.THEME.takeIf { theme != null },
            TextProperty.FOOTER.takeIf { footer != null },
        )

    /** These choices over [under]; what neither sets stays as [under] has it. */
    internal fun over(under: TextLook): TextLook = TextLook(
        font = font ?: under.font,
        size = size ?: under.size,
        lineHeight = lineHeight ?: under.lineHeight,
        paragraphSpacing = paragraphSpacing ?: under.paragraphSpacing,
        margin = margin ?: under.margin,
        indent = indent ?: under.indent,
        justify = justify ?: under.justify,
        theme = theme ?: under.theme,
        footer = footer ?: under.footer,
        chosen = under.chosen + properties,
    )

    internal fun withNumber(number: TextNumber, value: Int): TextLookChoices = when (number) {
        TextNumber.SIZE -> copy(size = value)
        TextNumber.LINE_HEIGHT -> copy(lineHeight = value)
        TextNumber.PARAGRAPH_SPACING -> copy(paragraphSpacing = value)
        TextNumber.MARGIN -> copy(margin = value)
    }

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
    fun valueOf(number: TextNumber): Int = when (number) {
        TextNumber.SIZE -> size
        TextNumber.LINE_HEIGHT -> lineHeight
        TextNumber.PARAGRAPH_SPACING -> paragraphSpacing
        TextNumber.MARGIN -> margin
    }

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
    fun effective(): TextLook = shown(TextScope.THIS_WORK)

    /** The values the panel shows for [scope]: in "all", the general ones, without this work's. */
    fun shown(scope: TextScope): TextLook {
        val general = general.over(TextLook.DEFAULT)
        return (if (scope == TextScope.ALL_TEXT_WORKS) general else own?.over(general) ?: general).marked()
    }

    // Rule 6 on what shows, whichever set brought the indent and the space: a work's own 0 under a
    // general "no indent" would run the paragraphs together.
    private fun TextLook.marked(): TextLook =
        if (indent || paragraphSpacing >= MIN_INDENTLESS_SPACING) this
        else copy(paragraphSpacing = indentless(paragraphSpacing), chosen = chosen + TextProperty.PARAGRAPH_SPACING)

    fun change(scope: TextScope, edit: TextEdit): TextSettings {
        val before = shown(scope)
        val target = (if (scope == TextScope.ALL_TEXT_WORKS) general else own) ?: TextLookChoices.NONE
        val changed = when (edit) {
            is TextEdit.Font -> target.copy(font = edit.font)
            is TextEdit.Number, is TextEdit.Step -> {
                val number = if (edit is TextEdit.Number) edit.number else (edit as TextEdit.Step).number
                val wanted = when (edit) {
                    is TextEdit.Number -> edit.value
                    else -> before.valueOf(number) + if ((edit as TextEdit.Step).up) number.step else -number.step
                }
                val value = bounded(number, wanted, before.indent)
                // A change that changes nothing, as minus at the floor, gives no setting of its own.
                if (value == before.valueOf(number)) return this
                target.withNumber(number, value)
            }
            is TextEdit.Indent -> {
                // A paragraph never goes unmarked: no indent and no space would run them together.
                val spacing = if (!edit.on && before.paragraphSpacing < MIN_INDENTLESS_SPACING) indentless(before.paragraphSpacing) else null
                target.copy(indent = edit.on, paragraphSpacing = spacing ?: target.paragraphSpacing)
            }
            is TextEdit.Justify -> target.copy(justify = edit.on)
            is TextEdit.Theme -> target.copy(theme = edit.theme)
            is TextEdit.Footer -> target.copy(footer = edit.footer)
        }
        return if (scope == TextScope.ALL_TEXT_WORKS) copy(general = changed) else copy(own = changed)
    }

    private fun bounded(number: TextNumber, value: Int, indent: Boolean): Int {
        val floor = if (number == TextNumber.PARAGRAPH_SPACING && !indent) MIN_INDENTLESS_SPACING else number.min
        return value.coerceIn(floor, number.max)
    }

    /**
     * In "all", the general defaults come back and the work keeps its own; in "this work", the work
     * drops its own and uses the general ones. Neither touches the mode.
     */
    fun restore(scope: TextScope): Pair<TextSettings, TextRestore> = when {
        scope == TextScope.ALL_TEXT_WORKS -> copy(general = TextLookChoices.NONE) to TextRestore.GENERAL_DEFAULTS
        own != null -> copy(own = null) to TextRestore.OWN_DISCARDED
        else -> this to TextRestore.ALREADY_GENERAL
    }

    companion object {
        val DEFAULT = TextSettings(TextLookChoices.NONE, null)

        /** Turning the indent off with no space between paragraphs raises it to 0.8 em (DESIGN, panel). */
        const val INDENTLESS_SPACING = 8

        /** With no indent, the space between paragraphs stays at 0.4 em or more. */
        const val MIN_INDENTLESS_SPACING = 4

        /** The space a paragraph gets when the indent goes: 0.8 em from none, the floor from less than it. */
        private fun indentless(spacing: Int) = if (spacing == 0) INDENTLESS_SPACING else MIN_INDENTLESS_SPACING
    }
}

/** A page theme's colors, as 0xAARRGGBB. */
data class PageColors(val background: Long, val text: Long, val secondary: Long)

object PageThemes {
    /** "Auto" follows the system's light or dark; "Dark" is the app's own dark background. */
    fun colors(theme: PageTheme, systemDark: Boolean): PageColors = when (theme) {
        PageTheme.AUTO -> colors(if (systemDark) PageTheme.DARK else PageTheme.LIGHT, systemDark)
        // The app's dark background and text, "Moss and amber" (slice 1).
        PageTheme.DARK -> PageColors(0xFF11100E, 0xFFE9E8E5, 0xFFAFAEAA)
        // The board's values (SinteseLeitorPaginado, 10/10/2026).
        PageTheme.BLACK -> PageColors(0xFF000000, 0xFFD9D7DE, 0xFF9896A6)
        PageTheme.SEPIA -> PageColors(0xFFEFE4CC, 0xFF3A2E20, 0xFF6A5944)
        PageTheme.LIGHT -> PageColors(0xFFF7F6F3, 0xFF1C1A17, 0xFF565552)
    }
}
