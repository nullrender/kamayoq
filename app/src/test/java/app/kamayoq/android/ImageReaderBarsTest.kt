package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the image reader's clean screen and its bars (DESIGN, "Leitor de imagem").
@RunWith(RobolectricTestRunner::class)
class ImageReaderBarsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val chapter = OpenedChapter(
        "chapter",
        List(5) { PageImage(100, 300) { ByteArrayInputStream(ByteArray(0)) } },
        ChapterProgress.NEVER_OPENED,
    ) {}

    private fun show(mode: ImageMode, onPreviousChapter: (() -> Unit)? = null) = compose.setContent {
        ImageReader(
            app.store, chapter, OpeningTurns(), 0, "Kaoru Hana wa Rin to Saku · Cap. 2", "Cap. 3", "test-device", detectedAs(mode), {}, {},
            place = ChapterPlace(2, 5),
            onPreviousChapter = onPreviousChapter,
        )
    }

    private fun showControls() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()
    }

    @Test
    fun `dado Páginas, então o contador diz a imagem entre quantas e acompanha a virada`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT)
        compose.onNodeWithText("1 / 5").assertIsDisplayed()

        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.waitForIdle()

        compose.onNodeWithText("2 / 5").assertIsDisplayed()
    }

    @Test
    fun `dado a Tira, então não há contador na tela limpa`() {
        show(ImageMode.STRIP)
        compose.waitForIdle()

        compose.onNodeWithText("1 / 5").assertDoesNotExist()
    }

    @Test
    fun `dado os controles, então a barra de cima diz o capítulo entre quantos e o modo`() {
        show(ImageMode.PAGES_RIGHT_TO_LEFT)

        showControls()

        val place = app.getString(R.string.reader_chapter_of, 2, 5)
        compose.onNodeWithText(app.getString(R.string.reader_image_place, place, app.getString(R.string.reader_image_mode_rtl))).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.reader_back_to_work)).assertIsDisplayed()
    }

    @Test
    fun `dado os controles e um capítulo antes, quando toco em Capítulo anterior, então ele é pedido`() {
        var asked = false
        show(ImageMode.STRIP) { asked = true }
        showControls()

        compose.onNodeWithContentDescription(app.getString(R.string.reader_previous_chapter)).performClick()

        assertTrue(asked)
    }

    @Test
    fun `dado o primeiro capítulo, então Capítulo anterior aparece desligado`() {
        show(ImageMode.STRIP)

        showControls()

        compose.onNodeWithContentDescription(app.getString(R.string.reader_previous_chapter)).assertIsNotEnabled()
    }

    @Test
    fun `dado Páginas, quando o TalkBack leva o deslizante à imagem 4, então a imagem 4 ocupa a tela`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT)
        showControls()

        compose.onNodeWithContentDescription(app.getString(R.string.reader_page_slider))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(app.getString(R.string.reader_page_label, 4, 5)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.reader_page_label, 1, 5)).assertIsNotDisplayed()
    }
}
