package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The interface's weights drawn by the real Android text stack, which Robolectric does not run.
 * On a phone with the system's bold text on (font weight adjustment), every weight comes out
 * heavier, as the person asked; this test runs without it, so the weights must differ.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceWeightTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sample = "Sua biblioteca está vazia"

    // Dark pixels of black text on white: a heavier weight leaves more ink.
    private fun ink(tag: String): Int {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        var dark = 0
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
            if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 128) dark++
        }
        return dark
    }

    private fun assertLighter(light: (TextStyle) -> TextStyle, heavy: (TextStyle) -> TextStyle, base: @Composable () -> TextStyle) {
        compose.setContent {
            KamayoqTheme(dark = false) {
                val style = base()
                Column(modifier = Modifier.background(Color.White)) {
                    Text(sample, style = light(style), color = Color.Black, modifier = Modifier.testTag("light").background(Color.White).padding(4.dp))
                    Text(sample, style = heavy(style), color = Color.Black, modifier = Modifier.testTag("heavy").background(Color.White).padding(4.dp))
                }
            }
        }
        val light = ink("light")
        val heavy = ink("heavy")
        assertTrue("o peso leve deixou $light pixels de tinta e o pesado $heavy", light < heavy * 0.85)
    }

    @Test
    fun `dado o texto da interface em Figtree quando sai em peso normal e em negrito então o normal deixa bem menos tinta`() {
        assertLighter(
            light = { it.copy(fontWeight = FontWeight.Normal) },
            heavy = { it.copy(fontWeight = FontWeight.Bold) },
            base = { MaterialTheme.typography.bodyLarge },
        )
    }

    @Test
    fun `dado um título em Bricolage Grotesque quando sai em peso médio e em extranegrito então o médio deixa bem menos tinta`() {
        assertLighter(
            light = { it.copy(fontWeight = FontWeight.Medium) },
            heavy = { it.copy(fontWeight = FontWeight.ExtraBold) },
            base = { MaterialTheme.typography.headlineMedium },
        )
    }
}
