package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the block after a chapter's last image (DESIGN, "Leitor de imagem", 5).
@RunWith(RobolectricTestRunner::class)
class ImageEndBlockTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    private val rows = listOf(
        ReaderChapterRow("c1", "Capítulo 1", read = true, percent = null),
        ReaderChapterRow("c2", "Capítulo 2", read = false, percent = null),
    )

    /** One image in Pages, so a touch on the right reaches the block. */
    private fun atTheEnd(next: String?, onNext: () -> Unit = {}, onBack: () -> Unit = {}) {
        val chapter = OpenedChapter("c2", listOf(PageImage(100, 150) { ByteArrayInputStream(ByteArray(0)) }), ChapterProgress.NEVER_OPENED) {}
        compose.setContent {
            ImageReader(
                app.store, chapter, OpeningTurns(), 0, "Teto x Egen · Capítulo 2", next, "test-device", detectedAs(ImageMode.PAGES_LEFT_TO_RIGHT),
                onNext, onBack,
                chapterRows = rows,
                chapterName = "Capítulo 2",
                workTitle = "Teto x Egen",
            )
        }
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.waitForIdle()
    }

    @Test
    fun `dado o fim de um capítulo com outro depois, então o bloco diz qual terminou e qual vem, e o botão abre o próximo`() {
        var next = false
        atTheEnd("Capítulo 3", onNext = { next = true })

        compose.onNodeWithText(text(R.string.reader_image_chapter_finished, "Capítulo 2").uppercase(Locale.getDefault())).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_image_up_next)).assertIsDisplayed()
        compose.onNodeWithText("Capítulo 3").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_image_read_next)).performClick()

        assertTrue(next)
    }

    @Test
    fun `dado o último capítulo disponível, então o bloco diz isso, nomeia a obra e volta à obra`() {
        var back = false
        atTheEnd(null, onBack = { back = true })

        compose.onNodeWithText(text(R.string.reader_image_last_available)).assertIsDisplayed()
        compose.onNodeWithText("Teto x Egen").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_back_to_work)).performClick()

        assertTrue(back)
    }

    @Test
    fun `dado o bloco de fim, quando toco em Capítulos, então a lista da obra abre sem sair do leitor`() {
        atTheEnd("Capítulo 3")

        compose.onNodeWithText(text(R.string.reader_chapters)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Capítulo 1").assertIsDisplayed()
    }

    // Gemini's review of slice 6, finding 7: in Pages the end block neither scrolled nor kept to the
    // screen's edges, so in landscape or with a large font its buttons could fall off the screen.
    @Test
    fun `dado o bloco de fim em Páginas numa tela baixa com a letra grande, então rolando se chega a Voltar à obra`() {
        val chapter = OpenedChapter("c2", listOf(PageImage(100, 150) { ByteArrayInputStream(ByteArray(0)) }), ChapterProgress.NEVER_OPENED) {}
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.height(320.dp)) {
                    ImageReader(
                        app.store, chapter, OpeningTurns(), 0, "Teto x Egen · Capítulo 2", "Capítulo 3", "test-device",
                        detectedAs(ImageMode.PAGES_LEFT_TO_RIGHT), {}, {},
                        chapterRows = rows, chapterName = "Capítulo 2", workTitle = "Teto x Egen",
                    )
                }
            }
        }
        // Inside the 320 dp the reader has, not the middle of the whole window.
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, 100.dp.toPx())) }
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.reader_back_to_work)).performScrollTo().assertIsDisplayed()
    }
}
