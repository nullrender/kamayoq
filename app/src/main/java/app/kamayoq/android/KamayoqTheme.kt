package app.kamayoq.android

import androidx.compose.foundation.isSystemInDarkTheme
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

private val unset = Color.Gray

/** Palette 2 of the design, "Musgo e âmbar", the default chosen on 07/10/2026. */
object MossAndAmber {
    val dark = Palette(unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset)
    val light = Palette(unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset, unset)
}

internal val LocalPalette = staticCompositionLocalOf { MossAndAmber.dark }

@Composable
fun KamayoqTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) MossAndAmber.dark else MossAndAmber.light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
    }
}
