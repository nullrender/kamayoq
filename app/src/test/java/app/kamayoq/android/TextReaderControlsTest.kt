package app.kamayoq.android

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
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

@RunWith(RobolectricTestRunner::class)
class TextReaderControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado os controles do livro quando tocados então cada botão executa sua ação com alvo de quarenta e oito dp`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            TextReaderControls(true, { actions += "previous" }, { actions += "contents" }, { actions += "next" })
        }
        for (label in listOf(R.string.reader_previous_page, R.string.reader_contents, R.string.reader_next_page)) {
            compose.onNodeWithText(context.getString(label))
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .performClick()
        }
        assertEquals(listOf("previous", "contents", "next"), actions)
    }

    @Test
    fun `dado o livro ainda abrindo então os controles não aceitam navegação`() {
        compose.setContent { TextReaderControls(false, {}, {}, {}) }
        for (label in listOf(R.string.reader_previous_page, R.string.reader_contents, R.string.reader_next_page)) {
            compose.onNodeWithText(context.getString(label)).assertIsNotEnabled()
        }
    }
}
