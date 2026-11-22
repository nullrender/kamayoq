package app.kamayoq.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Slice 6 of the v1 interface: the image reader with real images decoded on a device, in both modes.
@RunWith(AndroidJUnit4::class)
class ImageReaderDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** A page of [width] by [height] in one color, as PNG: a real image for the decoder. */
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun chapter(width: Int, height: Int): OpenedChapter {
        val pages = listOf(Color.RED, Color.GREEN).map { png(width, height, it) }
        return OpenedChapter("chapter", pages.map { bytes -> PageImage(width, height) { ByteArrayInputStream(bytes) } }, ChapterProgress.NEVER_OPENED) {}
    }

    private fun show(chapter: OpenedChapter, mode: ImageMode) = compose.setContent {
        ImageReader(
            compose.activity.applicationContext.let { (it as KamayoqApp).store }, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "emulator",
            ImageModeSettings(ImageModeChoice.AUTOMATIC, null, mode), {}, {},
        )
    }

    private fun tapAt(fraction: Float) {
        compose.onRoot().performTouchInput { click(Offset(width * fraction, height / 2f)) }
        compose.waitForIdle()
    }

    @Test
    fun `dado duas imagens reais em Páginas para a esquerda quando toco na esquerda duas vezes então elas abrem e a leitura chega ao bloco de fim`() {
        show(chapter(800, 1200), ImageMode.PAGES_RIGHT_TO_LEFT)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.reader_page_failed)).assertDoesNotExist()

        tapAt(0.1f)
        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_page_failed)).assertDoesNotExist()
        tapAt(0.1f)

        compose.onNodeWithText(text(R.string.reader_image_last_available)).assertIsDisplayed()
    }

    @Test
    fun `dado imagens reais na Tira quando escolho Páginas para a esquerda pelo Modo então a imagem 1 continua e a esquerda avança`() {
        show(chapter(800, 3000), ImageMode.STRIP)
        compose.waitForIdle()
        tapAt(0.5f)

        compose.onNodeWithText(text(R.string.reader_mode)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.reader_image_mode_rtl)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 1, 2)).assertIsDisplayed()
        tapAt(0.1f)
        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 2, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 1, 2)).assertIsNotDisplayed()
    }
}
