package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the pinch in Pages (FEATURES, part 5, "a pinça entra junto com o modo Páginas").
@RunWith(RobolectricTestRunner::class)
class PagedZoomTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun image(number: Int) = compose.onNodeWithContentDescription(app.getString(R.string.reader_page_label, number, 3))

    private val zoomedIn get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.getString(R.string.reader_page_zoomed))

    private fun show() {
        val chapter = OpenedChapter("chapter", List(3) { PageImage(100, 150) { ByteArrayInputStream(ByteArray(0)) } }, ChapterProgress.NEVER_OPENED) {}
        compose.setContent {
            ImageReader(app.store, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", detectedAs(ImageMode.PAGES_LEFT_TO_RIGHT), {}, {})
        }
        compose.waitForIdle()
    }

    /** Two fingers apart from the middle ([out]) or toward it. */
    private fun pinch(out: Boolean) {
        compose.onRoot().performTouchInput {
            // Inside the screen: a finger put down outside it never reaches the page.
            val near = 40f
            val far = width * 0.4f
            val (from, to) = if (out) near to far else far to near
            pinch(center - Offset(from, 0f), center - Offset(to, 0f), center + Offset(from, 0f), center + Offset(to, 0f))
        }
        compose.waitForIdle()
    }

    private fun swipe() {
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun `dado a página inteira, quando arrasto para a esquerda, então vira para a imagem 2`() {
        show()

        swipe()

        image(2).assertIsDisplayed()
    }

    @Test
    fun `dado a página ampliada com dois dedos, quando arrasto para a esquerda, então ela se move e não vira`() {
        show()

        pinch(out = true)
        image(1).assert(zoomedIn)
        swipe()

        image(1).assertIsDisplayed()
        image(2).assertIsNotDisplayed()
    }

    @Test
    fun `dado a página ampliada, quando os dedos se juntam de volta, então arrastar vira a página de novo`() {
        show()
        pinch(out = true)
        image(1).assert(zoomedIn)

        pinch(out = false)
        image(1).assert(!zoomedIn)
        swipe()

        image(2).assertIsDisplayed()
    }
}
