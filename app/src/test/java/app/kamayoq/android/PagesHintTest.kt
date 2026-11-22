package app.kamayoq.android

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 5: the hint on the first opening in Pages.
@RunWith(RobolectricTestRunner::class)
class PagesHintTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado a dica, então ela nomeia as três zonas, explica e fecha com Entendi`() {
        var dismissed = 0
        compose.setContent { KamayoqTheme { PagesHint(onDismiss = { dismissed++ }) } }

        for (zone in listOf(R.string.reader_hint_previous, R.string.reader_hint_controls, R.string.reader_hint_next)) {
            compose.onNodeWithText(context.getString(zone)).assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.reader_hint_text)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_hint_ok)).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, dismissed)
    }

    // Gemini's review of slice 5, finding 11: the veil was a clickable node with no label.
    @Test
    fun `dado a dica, então o único alvo de toque é o Entendi`() {
        compose.setContent { KamayoqTheme { PagesHint(onDismiss = {}) } }
        assertEquals(1, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
