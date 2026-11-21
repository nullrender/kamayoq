package app.kamayoq.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.kamayoq.core.library.WorkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The Work header's type label on the real colours of both themes (AGENTS.md: contrast measured on
// the real combinations). The action colour is only for what can be touched; a type label is not.
class TypeLabelColoursTest {

    // WCAG 2 contrast ratio, as KamayoqThemeTest measures it.
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `dado uma obra de quadrinhos, então o tipo no cabeçalho tem a cor de imagem, no escuro e no claro`() {
        for (palette in listOf(MossAndAmber.dark, MossAndAmber.light)) {
            assertEquals(palette.imageType, typeLabel(WorkType.COMICS, palette))
        }
    }

    @Test
    fun `dado uma novel ou um livro, então o tipo no cabeçalho tem a cor de texto, no escuro e no claro`() {
        for (palette in listOf(MossAndAmber.dark, MossAndAmber.light)) {
            assertEquals(palette.textType, typeLabel(WorkType.NOVELS, palette))
            assertEquals(palette.textType, typeLabel(WorkType.BOOKS, palette))
        }
    }

    @Test
    fun `dado o cabeçalho no fundo da tela, então o tipo tem 4,5 para 1 sobre ele em cada tipo, no escuro e no claro`() {
        for (palette in listOf(MossAndAmber.dark, MossAndAmber.light)) {
            for (type in WorkType.entries) {
                val ratio = contrast(typeLabel(type, palette), palette.background)
                assertTrue("$type: contrast $ratio", ratio >= 4.5)
            }
        }
    }
}
