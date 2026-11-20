package app.kamayoq.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The selection bar's labels on the real colours of both themes (AGENTS.md: contrast measured on
// the real combinations). On the S24, r641's grey labels on the grey bar looked disabled.
class SelectionBarColoursTest {

    // WCAG 2 contrast ratio, as KamayoqThemeTest measures it.
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `dado a barra da seleção no escuro e no claro, então os rótulos das ações têm 4,5 para 1 sobre ela`() {
        for (palette in listOf(MossAndAmber.dark, MossAndAmber.light)) {
            val ratio = contrast(selectionLabel(palette), palette.bar)
            assertTrue("contrast $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `dado a barra da seleção, então os rótulos não têm o cinza do texto secundário, que parece desativado`() {
        for (palette in listOf(MossAndAmber.dark, MossAndAmber.light)) {
            assertNotEquals(palette.secondaryText, selectionLabel(palette))
        }
    }
}
