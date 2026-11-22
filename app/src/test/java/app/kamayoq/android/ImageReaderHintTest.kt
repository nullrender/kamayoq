package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the hint on the first opening in Pages ← (DESIGN, "Leitor de imagem", 4).
@RunWith(RobolectricTestRunner::class)
class ImageReaderHintTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private fun text(id: Int) = app.getString(id)

    private fun show(mode: ImageMode, hint: Boolean, onSeen: () -> Unit = {}) {
        val chapter = OpenedChapter("chapter", List(3) { PageImage(100, 300) { ByteArrayInputStream(ByteArray(0)) } }, ChapterProgress.NEVER_OPENED) {}
        compose.setContent {
            ImageReader(
                app.store, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", detectedAs(mode), {}, {},
                rightToLeftHint = hint,
                onRightToLeftHintSeen = onSeen,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `dado a primeira abertura em Páginas para a esquerda, então a dica põe Próxima à esquerda e Anterior à direita`() {
        show(ImageMode.PAGES_RIGHT_TO_LEFT, hint = true)

        val next = compose.onNodeWithText(text(R.string.reader_hint_next)).fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithText(text(R.string.reader_hint_previous)).fetchSemanticsNode().boundsInRoot
        assertTrue("Next at ${next.left}, Previous at ${previous.left}", next.left < previous.left)
    }

    @Test
    fun `dado a dica, quando toco em Entendi, então ela some e fica guardada como vista`() {
        var seen = 0
        show(ImageMode.PAGES_RIGHT_TO_LEFT, hint = true) { seen++ }

        compose.onNodeWithText(text(R.string.reader_hint_ok)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.reader_hint_ok)).assertDoesNotExist()
        assertEquals(1, seen)
    }

    @Test
    fun `dado Páginas para a direita, então a dica de Páginas para a esquerda não aparece`() {
        show(ImageMode.PAGES_LEFT_TO_RIGHT, hint = true)

        compose.onNodeWithText(text(R.string.reader_hint_ok)).assertDoesNotExist()
    }

    @Test
    fun `dado a dica já vista, então Páginas para a esquerda abre sem ela`() {
        show(ImageMode.PAGES_RIGHT_TO_LEFT, hint = false)

        compose.onNodeWithText(text(R.string.reader_hint_ok)).assertDoesNotExist()
    }
}
