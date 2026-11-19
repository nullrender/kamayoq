package app.kamayoq.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The colour roles of the design (reader-design, DESIGN.md, "Paletas v2"). */
@Immutable
data class Palette(
    val background: Color,
    val card: Color,
    val bar: Color,
    val raised: Color,
    val line: Color,
    val text: Color,
    val secondaryText: Color,
    /** Only on things that can be touched: Continue, the main button of a screen. */
    val action: Color,
    val onAction: Color,
    /** Text content (novels, books): badges, counters and progress, never a button. */
    val textType: Color,
    val onTextType: Color,
    /** Image content (comics): badges, counters and progress, never a button. */
    val imageType: Color,
    val onImageType: Color,
    val error: Color,
    val onError: Color,
)

/**
 * Palette 2 of the design, "Musgo e âmbar", the default chosen on 07/10/2026: the values of the
 * boards (SinteseBiblioteca, `.g.p-2` and `.g.t-claro.p-2`). The error colours are the ones the
 * AjustesApp board took from Material.
 */
object MossAndAmber {
    val dark = Palette(
        background = Color(0xFF11100E),
        card = Color(0xFF1F1E1B),
        bar = Color(0xFF292824),
        raised = Color(0xFF31302D),
        line = Color(0xFF3E3D3A),
        text = Color(0xFFE9E8E5),
        secondaryText = Color(0xFFAFAEAA),
        action = Color(0xFF88C28A),
        onAction = Color(0xFF101810),
        textType = Color(0xFFF5C076),
        onTextType = Color(0xFF1B150C),
        imageType = Color(0xFF90BCE9),
        onImageType = Color(0xFF0F171F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
    val light = Palette(
        background = Color(0xFFF8F7F4),
        card = Color(0xFFFFFFFF),
        bar = Color(0xFFEEEDE8),
        raised = Color(0xFFE4E3DE),
        line = Color(0xFFD2D1CD),
        text = Color(0xFF1C1A17),
        secondaryText = Color(0xFF565552),
        action = Color(0xFF2F6D34),
        onAction = Color(0xFFFFFFFF),
        textType = Color(0xFF7A5103),
        onTextType = Color(0xFFFFFFFF),
        imageType = Color(0xFF265B8D),
        onImageType = Color(0xFFFFFFFF),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
    )
}

internal val LocalPalette = staticCompositionLocalOf { MossAndAmber.dark }

private fun Palette.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = action,
        onPrimary = onAction,
        primaryContainer = raised,
        onPrimaryContainer = text,
        secondary = action,
        onSecondary = onAction,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = action,
        onTertiary = onAction,
        tertiaryContainer = raised,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = bar,
        onSurfaceVariant = secondaryText,
        // Material tints raised surfaces with this colour; the background's own keeps them neutral.
        surfaceTint = background,
        surfaceBright = raised,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = bar,
        surfaceContainerHighest = raised,
        // The board's line is too faint for the edge of a text field, which needs 3:1 (WCAG 1.4.11);
        // it stays for dividers.
        outline = secondaryText,
        outlineVariant = line,
        // Neutral actions use the text colour on the inverted background (DESIGN.md, principle 4).
        inverseSurface = text,
        inverseOnSurface = background,
        inversePrimary = text,
        error = error,
        onError = onError,
        errorContainer = raised,
        onErrorContainer = error,
        scrim = Color.Black,
    )
}

@Composable
fun KamayoqTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) MossAndAmber.dark else MossAndAmber.light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(dark), typography = KamayoqTypography, content = content)
    }
}
