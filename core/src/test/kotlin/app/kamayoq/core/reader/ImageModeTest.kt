package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageModeTest {

    // What the chapter's images and metadata say is ImageModeDetection's (ImageModeDetectionTest).
    private fun settings(general: ImageModeChoice = ImageModeChoice.AUTOMATIC, own: ImageModeChoice? = null, detected: ImageMode = ImageMode.STRIP) =
        ImageModeSettings(general, own, detected)

    @Test
    fun `dado nenhuma escolha, então abre no detectado`() {
        assertEquals(ImageMode.PAGES_RIGHT_TO_LEFT, settings(detected = ImageMode.PAGES_RIGHT_TO_LEFT).effective)
    }

    @Test
    fun `dado todas as obras em Páginas para a direita, quando a obra não tem escolha própria, então abre em Páginas para a direita`() {
        assertEquals(ImageMode.PAGES_LEFT_TO_RIGHT, settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, detected = ImageMode.PAGES_RIGHT_TO_LEFT).effective)
    }

    @Test
    fun `dado a obra na Tira e todas em Páginas para a esquerda, então a obra abre na Tira`() {
        assertEquals(ImageMode.STRIP, settings(general = ImageModeChoice.PAGES_RIGHT_TO_LEFT, own = ImageModeChoice.STRIP).effective)
    }

    @Test
    fun `dado um mangá em Automático só nele e todas em Páginas para a direita, então ele abre pelo detectado`() {
        val mode = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, own = ImageModeChoice.AUTOMATIC, detected = ImageMode.PAGES_RIGHT_TO_LEFT).effective

        assertEquals(ImageMode.PAGES_RIGHT_TO_LEFT, mode)
    }

    @Test
    fun `dado a obra sem escolha própria, então só esta obra mostra marcada a escolha de todas`() {
        val shown = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT)

        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, shown.chosen(ImageScope.THIS_WORK))
        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, shown.chosen(ImageScope.ALL_IMAGE_WORKS))
    }

    @Test
    fun `dado a obra com escolha própria, então cada alcance mostra a sua`() {
        val shown = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, own = ImageModeChoice.STRIP)

        assertEquals(ImageModeChoice.STRIP, shown.chosen(ImageScope.THIS_WORK))
        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, shown.chosen(ImageScope.ALL_IMAGE_WORKS))
    }

    @Test
    fun `dado uma escolha só para esta obra, então a de todas não muda`() {
        val changed = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT).choose(ImageScope.THIS_WORK, ImageModeChoice.PAGES_RIGHT_TO_LEFT)

        assertEquals(ImageModeChoice.PAGES_RIGHT_TO_LEFT, changed.own)
        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, changed.general)
        assertEquals(ImageMode.PAGES_RIGHT_TO_LEFT, changed.effective)
    }

    @Test
    fun `dado a obra com escolha própria, quando escolhe para todas, então a obra continua com a dela`() {
        val changed = settings(own = ImageModeChoice.STRIP).choose(ImageScope.ALL_IMAGE_WORKS, ImageModeChoice.PAGES_LEFT_TO_RIGHT)

        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, changed.general)
        assertEquals(ImageModeChoice.STRIP, changed.own)
        assertEquals(ImageMode.STRIP, changed.effective)
    }

    @Test
    fun `dado restaurar em só esta obra, então ela volta a seguir a escolha de todas`() {
        val restored = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, own = ImageModeChoice.STRIP).restore(ImageScope.THIS_WORK)

        assertNull(restored.own)
        assertEquals(ImageMode.PAGES_LEFT_TO_RIGHT, restored.effective)
    }

    @Test
    fun `dado restaurar em todas, então todas voltam ao Automático e a obra com escolha própria fica com ela`() {
        val restored = settings(general = ImageModeChoice.PAGES_LEFT_TO_RIGHT, own = ImageModeChoice.STRIP).restore(ImageScope.ALL_IMAGE_WORKS)

        assertEquals(ImageModeChoice.AUTOMATIC, restored.general)
        assertEquals(ImageModeChoice.STRIP, restored.own)
    }
}
