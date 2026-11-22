package app.kamayoq.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.PageThemes
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReadingFont
import app.kamayoq.core.reader.TextEdit
import app.kamayoq.core.reader.TextNumber
import app.kamayoq.core.reader.TextProperty
import app.kamayoq.core.reader.TextRestore
import app.kamayoq.core.reader.TextScope
import app.kamayoq.core.reader.TextSettings
import java.text.NumberFormat

/**
 * The settings panel of the text reader (board SinteseLeitorPaginado; DESIGN, "Painel de formatação").
 * A sheet with no X: touching the page, dragging it down or Back closes it. The text stays in sight
 * above it, so each change shows at once. Choices are marked by a neutral color and a check, never by
 * the type's or the action's color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextSettingsPanel(
    settings: TextSettings,
    onSettings: (TextSettings) -> Unit,
    type: WorkType,
    flow: ReaderFlow,
    onFlow: (ReaderFlow) -> Unit,
    touchExploration: Boolean,
    hyphenates: Boolean,
    systemDark: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { SheetHandle() },
        scrimColor = Color.Transparent,
    ) {
        TextSettingsContent(settings, onSettings, type, flow, onFlow, touchExploration, hyphenates, systemDark)
    }
}

/** What the panel holds, apart from its sheet, so the screen tests reach it with no window of its own. */
@Composable
internal fun TextSettingsContent(
    settings: TextSettings,
    onSettings: (TextSettings) -> Unit,
    type: WorkType,
    flow: ReaderFlow,
    onFlow: (ReaderFlow) -> Unit,
    touchExploration: Boolean,
    hyphenates: Boolean,
    systemDark: Boolean,
) {
    // "All" first, as the design's default: a change made without looking at the selector reaches every text work.
    var scope by rememberSaveable { mutableStateOf(TextScope.ALL_TEXT_WORKS) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var notice by remember { mutableStateOf<Pair<TextRestore, TextSettings?>?>(null) }
    val shown = settings.shown(scope)
    fun edit(change: TextEdit) {
        notice = null
        onSettings(settings.change(scope, change))
    }
    val ownNames = settings.own?.properties?.map { propertyName(it) }?.joinToString(", ")

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_panel_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val restoreDescription = stringResource(if (scope == TextScope.ALL_TEXT_WORKS) R.string.reader_restore_all_description else R.string.reader_restore_work_description)
            TextButton(
                onClick = {
                    val before = settings
                    val (after, outcome) = settings.restore(scope)
                    onSettings(after)
                    notice = outcome to before.takeIf { outcome != TextRestore.ALREADY_GENERAL }
                },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = restoreDescription },
            ) {
                // Its description is the fuller one; the short text would be read after it.
                Text(
                    stringResource(if (scope == TextScope.ALL_TEXT_WORKS) R.string.reader_restore_all else R.string.reader_restore_work),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Label(stringResource(R.string.reader_scope))
        Choices {
            Choice(scope == TextScope.THIS_WORK, stringResource(R.string.reader_scope_work), Modifier.weight(1f)) { scope = TextScope.THIS_WORK }
            Choice(scope == TextScope.ALL_TEXT_WORKS, stringResource(R.string.reader_scope_all), Modifier.weight(1f)) { scope = TextScope.ALL_TEXT_WORKS }
        }
        Note(
            when {
                scope == TextScope.ALL_TEXT_WORKS && ownNames != null -> stringResource(R.string.reader_note_all_own, ownNames)
                scope == TextScope.ALL_TEXT_WORKS -> stringResource(R.string.reader_note_all)
                ownNames != null -> stringResource(R.string.reader_note_work_own, ownNames)
                else -> stringResource(R.string.reader_note_work)
            },
        )
        notice?.let { (outcome, before) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp).announced(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (outcome) {
                        TextRestore.GENERAL_DEFAULTS -> before?.own?.properties?.map { propertyName(it) }?.joinToString(", ")
                            ?.let { stringResource(R.string.reader_restored_all_keeps, it) } ?: stringResource(R.string.reader_restored_all)
                        TextRestore.OWN_DISCARDED -> stringResource(R.string.reader_restored_work)
                        TextRestore.ALREADY_GENERAL -> stringResource(R.string.reader_already_general)
                    },
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                if (before != null) {
                    TextButton(onClick = { notice = null; onSettings(before) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.undo), color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
        Choices(Modifier.padding(top = 12.dp)) {
            Choice(tab == 0, stringResource(R.string.reader_tab_text), Modifier.weight(1f), role = Role.Tab) { tab = 0 }
            Choice(tab == 1, stringResource(R.string.reader_tab_look), Modifier.weight(1f), role = Role.Tab) { tab = 1 }
        }
        if (tab == 0) {
            Label(stringResource(R.string.reader_font))
            val fonts = readingFonts()
            Choices {
                for (font in ReadingFont.entries) {
                    val (short, full) = FONT_NAMES.getValue(font)
                    Choice(shown.font == font, short, Modifier.weight(1f), fontFamily = fonts[font], description = full) { edit(TextEdit.Font(font)) }
                }
            }
            NumberRow(TextNumber.SIZE, shown.size, R.string.reader_size, R.string.reader_size_slider, R.string.reader_size_down, R.string.reader_size_up, ::edit)
            NumberRow(TextNumber.LINE_HEIGHT, shown.lineHeight, R.string.reader_line_height, R.string.reader_line_height, R.string.reader_line_height_down, R.string.reader_line_height_up, ::edit)
            NumberRow(
                TextNumber.PARAGRAPH_SPACING, shown.paragraphSpacing, R.string.reader_paragraphs, R.string.reader_paragraphs_slider,
                R.string.reader_paragraphs_down, R.string.reader_paragraphs_up, ::edit,
                floor = if (shown.indent) TextNumber.PARAGRAPH_SPACING.min else TextSettings.MIN_INDENTLESS_SPACING,
            )
            NumberRow(TextNumber.MARGIN, shown.margin, R.string.reader_margins, R.string.reader_margins_slider, R.string.reader_margins_down, R.string.reader_margins_up, ::edit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Toggle(shown.indent, stringResource(R.string.reader_indent), Modifier.weight(1f)) { edit(TextEdit.Indent(it)) }
                Toggle(shown.justify, stringResource(R.string.reader_justify), Modifier.weight(1.6f)) { edit(TextEdit.Justify(it)) }
            }
            if (shown.justify && !hyphenates) Note(stringResource(R.string.reader_no_hyphenation))
        } else {
            Label(stringResource(R.string.reader_mode))
            Choices {
                Choice(flow == ReaderFlow.SCROLL, stringResource(R.string.reader_mode_scroll), Modifier.weight(1f), enabled = !touchExploration) { onFlow(ReaderFlow.SCROLL) }
                Choice(flow == ReaderFlow.PAGES, stringResource(R.string.reader_mode_pages), Modifier.weight(1f), enabled = !touchExploration) { onFlow(ReaderFlow.PAGES) }
            }
            Note(
                stringResource(
                    when {
                        touchExploration -> R.string.reader_mode_talkback
                        type == WorkType.NOVELS -> R.string.reader_mode_default_novels
                        else -> R.string.reader_mode_default_books
                    },
                ),
            )
            Label(stringResource(R.string.reader_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
                for (theme in PageTheme.entries) ThemeChoice(theme, shown.theme == theme, systemDark, Modifier.weight(1f)) { edit(TextEdit.Theme(theme)) }
            }
            Note(stringResource(R.string.reader_theme_note))
            Label(stringResource(R.string.reader_footer))
            Choices {
                Choice(shown.footer == FooterContent.ALL, stringResource(R.string.reader_footer_all), Modifier.weight(1f)) { edit(TextEdit.Footer(FooterContent.ALL)) }
                Choice(shown.footer == FooterContent.PROGRESS, stringResource(R.string.reader_footer_progress), Modifier.weight(1.3f)) { edit(TextEdit.Footer(FooterContent.PROGRESS)) }
                Choice(shown.footer == FooterContent.NOTHING, stringResource(R.string.reader_footer_nothing), Modifier.weight(1f)) { edit(TextEdit.Footer(FooterContent.NOTHING)) }
            }
        }
    }
}

private val FONT_NAMES = mapOf(
    ReadingFont.LITERATA to ("Literata" to "Literata"),
    ReadingFont.GARAMOND to ("Garamond" to "EB Garamond"),
    ReadingFont.PLEX to ("Plex" to "IBM Plex Serif"),
    ReadingFont.ATKINSON to ("Atkinson" to "Atkinson Hyperlegible"),
)

private val FONT_FILES = mapOf(
    ReadingFont.LITERATA to "Literata.ttf",
    ReadingFont.GARAMOND to "EBGaramond.ttf",
    ReadingFont.PLEX to "IBMPlexSerif-Regular.ttf",
    ReadingFont.ATKINSON to "AtkinsonHyperlegible-Regular.ttf",
)

/** Each font's button is written in that font (DESIGN, panel), from the same files the page reads. */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun readingFonts(): Map<ReadingFont, FontFamily> {
    val assets = LocalContext.current.assets
    return remember(assets) { FONT_FILES.mapValues { (_, file) -> FontFamily(Font("reader/fonts/$file", assets)) } }
}

@Composable
private fun propertyName(property: TextProperty): String = stringResource(
    when (property) {
        TextProperty.FONT -> R.string.reader_property_font
        TextProperty.SIZE -> R.string.reader_property_size
        TextProperty.LINE_HEIGHT -> R.string.reader_property_line_height
        TextProperty.PARAGRAPH_SPACING -> R.string.reader_property_paragraphs
        TextProperty.MARGIN -> R.string.reader_property_margins
        TextProperty.INDENT -> R.string.reader_property_indent
        TextProperty.JUSTIFY -> R.string.reader_property_justify
        TextProperty.THEME -> R.string.reader_property_theme
        TextProperty.FOOTER -> R.string.reader_property_footer
    },
)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.secondaryText, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Choices(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(modifier = modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

/** One option of a group; the chosen one in the neutral inverted colors, with a check. */
@Composable
private fun Choice(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    fontFamily: FontFamily? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        modifier = modifier.heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.text else Color.Transparent,
        contentColor = if (selected) palette.background else palette.text,
        border = if (selected) null else BorderStroke(1.dp, palette.line),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
            Text(
                (if (selected) "✓ " else "") + label, fontFamily = fontFamily, maxLines = 2, style = MaterialTheme.typography.bodyMedium,
                modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
            )
        }
    }
}

@Composable
private fun Toggle(on: Boolean, label: String, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Surface(
        modifier = modifier.heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        shape = RoundedCornerShape(12.dp),
        color = if (on) palette.text else Color.Transparent,
        contentColor = if (on) palette.background else palette.text,
        border = if (on) null else BorderStroke(1.dp, palette.line),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
            Text((if (on) "✓ " else "") + label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A theme shown in its own colors; the chosen one keeps them and gets a neutral border and a check. */
@Composable
private fun ThemeChoice(theme: PageTheme, selected: Boolean, systemDark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = PageThemes.colors(theme, systemDark)
    val name = stringResource(THEME_NAMES.getValue(theme))
    val description = if (theme == PageTheme.AUTO) stringResource(R.string.reader_theme_auto_description) else name
    Column(
        modifier = modifier.heightIn(min = 56.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) LocalPalette.current.text else LocalPalette.current.line, RoundedCornerShape(12.dp))
            .background(Color(colors.background), RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Aa", color = Color(colors.text), fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics {})
        Text((if (selected) "✓ " else "") + name, color = Color(colors.text), fontSize = 11.sp, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

private val THEME_NAMES = mapOf(
    PageTheme.AUTO to R.string.reader_theme_auto,
    PageTheme.DARK to R.string.reader_theme_dark,
    PageTheme.BLACK to R.string.reader_theme_black,
    PageTheme.SEPIA to R.string.reader_theme_sepia,
    PageTheme.LIGHT to R.string.reader_theme_light,
)

/** A slider with − and + on each side and the value, as the board draws them. */
@Composable
private fun NumberRow(
    number: TextNumber,
    value: Int,
    label: Int,
    sliderLabel: Int,
    less: Int,
    more: Int,
    onEdit: (TextEdit) -> Unit,
    floor: Int = number.min,
) {
    val sliderDescription = stringResource(sliderLabel)
    val lessDescription = stringResource(less)
    val moreDescription = stringResource(more)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 84.dp))
        IconButton(
            onClick = { onEdit(TextEdit.Step(number, up = false)) },
            enabled = value > floor,
            modifier = Modifier.size(48.dp).semantics { contentDescription = lessDescription },
        ) {
            Text("−", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onEdit(TextEdit.Number(number, number.min + Math.round((it - number.min) / number.step) * number.step)) },
            valueRange = number.min.toFloat()..number.max.toFloat(),
            steps = (number.max - number.min) / number.step - 1,
            modifier = Modifier.weight(1f).semantics { contentDescription = sliderDescription },
        )
        IconButton(
            onClick = { onEdit(TextEdit.Step(number, up = true)) },
            enabled = value < number.max,
            modifier = Modifier.size(48.dp).semantics { contentDescription = moreDescription },
        ) {
            Text("+", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(shownValue(number, value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 32.dp))
    }
}

/** Sizes and margins as whole numbers; line height and paragraph spacing, kept in tenths, as "1,5". */
@Composable
private fun shownValue(number: TextNumber, value: Int): String = when (number) {
    TextNumber.SIZE, TextNumber.MARGIN -> value.toString()
    TextNumber.LINE_HEIGHT, TextNumber.PARAGRAPH_SPACING -> {
        val locale = LocalConfiguration.current.locales[0]
        NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(value / 10.0)
    }
}
