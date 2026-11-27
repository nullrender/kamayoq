package app.kamayoq.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

// Gemini's review of slice 6, finding 5: the counter "12 / 48" over a white manga page read at 2.7:1.
class CounterColoursTest {

    // WCAG 2 contrast ratio, from the relative luminance Compose computes the same way.
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `dado o contador sobre uma página branca, cinza ou preta, então o texto tem pelo menos 4,5 para 1`() {
        for (page in listOf(Color.White, Color(0xFFBDBDBD), Color(0xFF808080), Color.Black)) {
            val background = CounterColors.background.compositeOver(page)
            val text = CounterColors.text.compositeOver(background)
            val ratio = contrast(text, background)
            assertTrue("Over $page: ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }
}
