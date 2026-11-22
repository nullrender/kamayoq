package app.kamayoq.core.reader

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageThemesTest {

    // WCAG 2 relative luminance and contrast ratio, from the sRGB channels.
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `dado cada tema da página, então o texto e o texto secundário têm contraste de 4,5 para 1 sobre o fundo`() {
        val failures = PageTheme.entries.flatMap { theme ->
            listOf(true, false).flatMap { dark ->
                val colors = PageThemes.colors(theme, systemDark = dark)
                listOf("texto" to colors.text, "secundário" to colors.secondary).mapNotNull { (name, color) ->
                    val ratio = contrast(color, colors.background)
                    "$theme (sistema escuro: $dark): $name %.2f".format(ratio).takeIf { ratio < 4.5 }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `dado o tema Auto, então ele é o Escuro com o sistema escuro e o Claro com o sistema claro`() {
        assertEquals(PageThemes.colors(PageTheme.DARK, systemDark = true), PageThemes.colors(PageTheme.AUTO, systemDark = true))
        assertEquals(PageThemes.colors(PageTheme.LIGHT, systemDark = false), PageThemes.colors(PageTheme.AUTO, systemDark = false))
    }

    @Test
    fun `dado os temas, então cada um tem o fundo da prancha, e o Escuro é o fundo escuro do app`() {
        assertEquals(0xFF11100E, PageThemes.colors(PageTheme.DARK, systemDark = false).background)
        assertEquals(0xFF000000, PageThemes.colors(PageTheme.BLACK, systemDark = false).background)
        assertEquals(0xFFEFE4CC, PageThemes.colors(PageTheme.SEPIA, systemDark = true).background)
        assertEquals(0xFFF7F6F3, PageThemes.colors(PageTheme.LIGHT, systemDark = true).background)
    }
}
