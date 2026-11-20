package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KamayoqThemeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // WCAG 2 contrast ratio, from the relative luminance Compose computes the same way.
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertReadable(name: String, palette: Palette) {
        val texts = mapOf(
            "texto" to palette.text,
            "texto secundário" to palette.secondaryText,
            "ação" to palette.action,
            "tipo texto" to palette.textType,
            "tipo imagem" to palette.imageType,
            "erro" to palette.error,
        )
        val grounds = mapOf("fundo" to palette.background, "cartão" to palette.card, "barra" to palette.bar, "degrau" to palette.raised)
        val onColours = listOf(
            Triple("sobre a ação", palette.onAction, palette.action),
            Triple("sobre o tipo texto", palette.onTextType, palette.textType),
            Triple("sobre o tipo imagem", palette.onImageType, palette.imageType),
            Triple("sobre o erro", palette.onError, palette.error),
        )
        val failures = texts.flatMap { (text, colour) ->
            grounds.mapNotNull { (ground, behind) ->
                val ratio = contrast(colour, behind)
                "$name: $text sobre $ground, %.2f".format(ratio).takeIf { ratio < 4.5 }
            }
        } + onColours.mapNotNull { (pair, front, behind) ->
            val ratio = contrast(front, behind)
            "$name: texto $pair, %.2f".format(ratio).takeIf { ratio < 4.5 }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `dado a paleta Musgo e âmbar, então cada cor de texto tem contraste de 4,5 para 1 sobre fundo, cartão e barra, no escuro e no claro`() {
        assertReadable("escuro", MossAndAmber.dark)
        assertReadable("claro", MossAndAmber.light)
    }

    private fun schemeOf(dark: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent { KamayoqTheme(dark = dark) { scheme = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        return scheme
    }

    private fun assertFollows(palette: Palette, scheme: ColorScheme) {
        assertEquals(palette.background, scheme.background)
        assertEquals(palette.background, scheme.surface)
        assertEquals(palette.text, scheme.onSurface)
        assertEquals(palette.secondaryText, scheme.onSurfaceVariant)
        assertEquals(palette.card, scheme.surfaceContainer)
        assertEquals(palette.bar, scheme.surfaceContainerHigh)
        assertEquals(palette.action, scheme.primary)
        assertEquals(palette.onAction, scheme.onPrimary)
        assertEquals(palette.error, scheme.error)
    }

    @Test
    fun `dado o tema escuro, quando o app desenha, então fundo, texto, cartão e ação vêm da paleta escura`() {
        assertFollows(MossAndAmber.dark, schemeOf(dark = true))
    }

    @Test
    fun `dado o tema claro, quando o app desenha, então fundo, texto, cartão e ação vêm da paleta clara`() {
        assertFollows(MossAndAmber.light, schemeOf(dark = false))
    }

    // Material draws a notice's text in inverseOnSurface and its button, Undo, in inversePrimary, both
    // over inverseSurface. On the S24, with build r598, Undo had the notice's own colour and could
    // not be seen.
    private fun assertNoticeReadable(name: String, scheme: ColorScheme) {
        val failures = listOf("texto" to scheme.inverseOnSurface, "botão" to scheme.inversePrimary).mapNotNull { (part, colour) ->
            val ratio = contrast(colour, scheme.inverseSurface)
            "$name: $part do aviso sobre o aviso, %.2f".format(ratio).takeIf { ratio < 4.5 }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `dado o tema escuro, quando um aviso traz Desfazer, então o texto e o botão têm contraste de 4,5 para 1 sobre o aviso`() {
        assertNoticeReadable("escuro", schemeOf(dark = true))
    }

    @Test
    fun `dado o tema claro, quando um aviso traz Desfazer, então o texto e o botão têm contraste de 4,5 para 1 sobre o aviso`() {
        assertNoticeReadable("claro", schemeOf(dark = false))
    }
}
