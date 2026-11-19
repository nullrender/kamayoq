package app.kamayoq.android

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Both files are variable fonts (google/fonts, third_party/fonts/UPSTREAM.md): each weight is the
// same file set to that weight, so the APK carries one file per family.
@OptIn(ExperimentalTextApi::class)
private fun weighted(@FontRes file: Int, weight: Int) =
    Font(file, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** The interface's letters (reader-design, DESIGN.md, "Tipografia"). */
object KamayoqFonts {
    /** Bricolage Grotesque, 500 to 800: the app's name, screen and section titles, a work's title. */
    val titles: FontFamily = FontFamily(listOf(500, 600, 700, 800).map { weighted(R.font.bricolage_grotesque, it) })

    /** Figtree, 400 to 700: the rest of the interface. */
    val interfaceText: FontFamily = FontFamily(listOf(400, 500, 600, 700).map { weighted(R.font.figtree, it) })
}

private fun title(size: Int, weight: Int, spacing: Double = 0.0) =
    TextStyle(fontFamily = KamayoqFonts.titles, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * 1.2).sp, letterSpacing = spacing.sp)

private fun text(size: Int, weight: Int, height: Double = 1.35) =
    TextStyle(fontFamily = KamayoqFonts.interfaceText, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * height).sp)

// Sizes of DESIGN.md: screen title 26/700, the empty Library's sentence 27/600, the app's name
// 23/700, section title 18/600, item title 15/600, interface text 13 to 14, labels 12, never less.
internal val KamayoqTypography = Typography(
    displayLarge = title(45, 700, -0.5),
    displayMedium = title(36, 700, -0.5),
    displaySmall = title(30, 700, -0.4),
    headlineLarge = title(27, 600, -0.3),
    headlineMedium = title(26, 700, -0.4),
    headlineSmall = title(23, 700, -0.3),
    titleLarge = title(18, 600),
    titleMedium = text(15, 600),
    titleSmall = text(14, 600),
    bodyLarge = text(15, 400, 1.4),
    bodyMedium = text(14, 400, 1.4),
    bodySmall = text(12, 400),
    labelLarge = text(14, 600),
    labelMedium = text(12, 600),
    labelSmall = text(12, 500),
)
