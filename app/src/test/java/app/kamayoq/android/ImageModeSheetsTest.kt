package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the quick Mode sheet and the image reader's settings (board SinteseAjustesImagem).
@RunWith(RobolectricTestRunner::class)
class ImageModeSheetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    private val manga = ImageModeSettings(ImageModeChoice.AUTOMATIC, own = null, detected = ImageMode.PAGES_RIGHT_TO_LEFT)

    @Test
    fun `dado a folha rápida, então ela mostra o detectado no Automático e marca a escolha da obra`() {
        compose.setContent { KamayoqTheme { ImageModeQuickContent(manga.copy(own = ImageModeChoice.STRIP)) {} } }

        val detected = text(R.string.reader_image_detected, text(R.string.reader_image_mode_rtl))
        compose.onNodeWithText("${text(R.string.reader_image_mode_automatic)}\n$detected").assertIsNotSelected()
        compose.onNodeWithText(text(R.string.reader_image_mode_strip), substring = true).assertIsSelected()
        compose.onNodeWithText(text(R.string.reader_image_mode_note)).assertExists()
    }

    @Test
    fun `dado a folha rápida, quando toco em Páginas para a direita, então essa é a escolha`() {
        var chosen: ImageModeChoice? = null
        compose.setContent { KamayoqTheme { ImageModeQuickContent(manga) { chosen = it } } }

        compose.onNodeWithText(text(R.string.reader_image_mode_ltr)).performClick()

        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, chosen)
    }

    @Test
    fun `dado a folha de ajustes, então ela começa em Esta obra e diz que a obra segue o modo de todas`() {
        compose.setContent { KamayoqTheme { ImageSettingsContent(manga) {} } }

        // The note also starts with "This work": the choice is the one that can be selected.
        compose.onNode(hasText(text(R.string.reader_scope_work), substring = true) and isSelectable()).assertIsSelected()
        compose.onNodeWithText(text(R.string.reader_image_note_work_follows)).assertExists()
    }

    @Test
    fun `dado a folha de ajustes, então o detectado fica só na folha rápida`() {
        compose.setContent { KamayoqTheme { ImageSettingsContent(manga) {} } }

        compose.onNodeWithText(text(R.string.reader_image_detected, text(R.string.reader_image_mode_rtl)), substring = true).assertDoesNotExist()
        // Chosen, it reads "✓ Automatic".
        compose.onNodeWithText(text(R.string.reader_image_mode_automatic), substring = true).assertIsSelected()
    }

    @Test
    fun `dado a folha de ajustes em Todas, quando escolho a Tira, então muda o geral e a obra continua seguindo`() {
        var settings by mutableStateOf(manga)
        compose.setContent { KamayoqTheme { ImageSettingsContent(settings) { settings = it } } }

        compose.onNodeWithText(text(R.string.reader_image_scope_all)).performClick()
        compose.onNodeWithText(text(R.string.reader_image_mode_strip)).performClick()

        assertEquals(ImageModeChoice.STRIP, settings.general)
        assertEquals(null, settings.own)
        compose.onNodeWithText(text(R.string.reader_image_note_all)).assertExists()
    }

    @Test
    fun `dado a obra com modo próprio, quando toco em Usar o modo geral, então ela volta a seguir todas`() {
        var settings by mutableStateOf(manga.copy(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, own = ImageModeChoice.STRIP))
        compose.setContent { KamayoqTheme { ImageSettingsContent(settings) { settings = it } } }
        compose.onNodeWithText(text(R.string.reader_image_note_work_own)).assertExists()

        compose.onNodeWithText(text(R.string.reader_image_restore_work)).performClick()

        assertEquals(null, settings.own)
        assertEquals(ImageMode.PAGES_LEFT_TO_RIGHT, settings.effective)
        compose.onNodeWithText(text(R.string.reader_image_note_work_follows)).assertExists()
    }

    @Test
    fun `dado a Tira na imagem 1, quando escolho Páginas para a esquerda pelo Modo, então a folha fecha, a imagem 1 continua e a escolha é guardada`() {
        val chapter = OpenedChapter("chapter", List(5) { PageImage(100, 300) { ByteArrayInputStream(ByteArray(0)) } }, ChapterProgress.NEVER_OPENED) {}
        var kept: ImageModeSettings? = null
        compose.setContent {
            ImageReader(
                app.store, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", detectedAs(ImageMode.STRIP), {}, {},
                onModeSettings = { kept = it },
            )
        }
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.reader_mode)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.reader_image_mode_rtl)).performClick()
        compose.waitForIdle()

        assertEquals(ImageModeChoice.PAGES_RIGHT_TO_LEFT, kept?.own)
        compose.onNodeWithText(text(R.string.reader_image_mode_note)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 1, 5)).assertIsDisplayed()
        // In Pages now: the left side goes forward.
        compose.onRoot().performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.reader_page_label, 2, 5)).assertIsDisplayed()
    }

    // Gemini's review of slice 6, finding 6: two lines at most cut "detected: Pages ←" with a large
    // font, and the four choices were two groups of two for TalkBack.
    @Test
    fun `dado a folha rápida, então as quatro escolhas são um grupo só`() {
        compose.setContent { KamayoqTheme { ImageModeQuickContent(manga) {} } }

        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    @Test
    fun `dado a letra do sistema no dobro numa tela estreita, então o detectado do Automático aparece inteiro`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                KamayoqTheme { Box(Modifier.width(320.dp)) { ImageModeQuickContent(manga) {} } }
            }
        }
        val detected = text(R.string.reader_image_detected, text(R.string.reader_image_mode_rtl))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(detected, substring = true, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

        assertEquals(false, layouts.single().hasVisualOverflow)
    }
}
