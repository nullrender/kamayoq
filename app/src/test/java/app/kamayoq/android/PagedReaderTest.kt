package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

// Slice 6 of the v1 interface: the image reader's Pages, left to right and right to left.
@RunWith(RobolectricTestRunner::class)
class PagedReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    // Pages taller than the screen: in the Strip only the first shows, so a page that turned shows apart.
    private fun chapter(pages: Int) = OpenedChapter(
        "chapter",
        List(pages) { PageImage(100, 300) { ByteArrayInputStream(ByteArray(0)) } },
        ChapterProgress.NEVER_OPENED,
    ) {}

    private fun show(mode: ImageMode, pages: Int = 3, turns: OpeningTurns = OpeningTurns(), round: Long = 0) = compose.setContent {
        ImageReader(app.store, chapter(pages), turns, round, "Obra · Cap. 1", null, "test-device", detectedAs(mode), {}, {})
    }

    private fun image(number: Int, of: Int = 3) = compose.onNodeWithContentDescription(app.getString(R.string.reader_page_label, number, of))

    private fun tapAt(fraction: Float) {
        compose.onRoot().performTouchInput { click(Offset(width * fraction, height / 2f)) }
        compose.waitForIdle()
    }

    private fun turnByTalkBack(label: String) {
        val matcher = SemanticsMatcher("has the action '$label'") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
        }
        val action = compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    @Test
    fun `dado Páginas para a esquerda, quando toco na esquerda, então a imagem 2 ocupa a tela e a 1 sai`() {
        show(ImageMode.PAGES_RIGHT_TO_LEFT)

        tapAt(0.1f)

        image(2).assertIsDisplayed()
        image(1).assertIsNotDisplayed()
    }

    @Test
    fun `dado Páginas para a direita, quando toco na direita, então a imagem 2 ocupa a tela e a 1 sai`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT)

        tapAt(0.9f)

        image(2).assertIsDisplayed()
        image(1).assertIsNotDisplayed()
    }

    @Test
    fun `dado Páginas para a direita, quando toco na esquerda da primeira imagem, então ela continua na tela`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT)

        tapAt(0.1f)

        image(1).assertIsDisplayed()
    }

    @Test
    fun `dado Páginas, quando o TalkBack avança depois da última imagem, então o bloco de fim aparece`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT, pages = 2)
        val next = app.getString(R.string.reader_next_page)

        turnByTalkBack(next)
        turnByTalkBack(next)

        compose.onNodeWithText(app.getString(R.string.reader_image_last_available)).assertIsDisplayed()
    }

    @Test
    fun `dado Páginas, quando a pessoa vira até o bloco de fim, então pede duas gravações e nenhuma da abertura`() {
        // Each save fails here (the chapter is not in the store), so the log counts the saves asked for.
        val turns = OpeningTurns()
        val round = runBlocking { turns.open { it } }
        show(ImageMode.PAGES_RIGHT_TO_LEFT, pages = 2, turns = turns, round = round)

        tapAt(0.1f)
        tapAt(0.1f)
        runBlocking { turns.waitForSaves() }

        assertEquals(2, ShadowLog.getLogsForTag("Kamayoq").count { it.msg == "Saving the reading position failed" })
    }
}
