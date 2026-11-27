package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ImagePagesTest {

    @Test
    fun `dado Páginas para a direita, então a esquerda volta, a direita avança e o meio mostra os controles`() {
        assertEquals(TapZone.PREVIOUS, ImagePages.zone(0.1, ImageMode.PAGES_LEFT_TO_RIGHT))
        assertEquals(TapZone.NEXT, ImagePages.zone(0.9, ImageMode.PAGES_LEFT_TO_RIGHT))
        assertEquals(TapZone.CONTROLS, ImagePages.zone(0.5, ImageMode.PAGES_LEFT_TO_RIGHT))
    }

    @Test
    fun `dado Páginas para a esquerda, então a esquerda avança e a direita volta`() {
        assertEquals(TapZone.NEXT, ImagePages.zone(0.1, ImageMode.PAGES_RIGHT_TO_LEFT))
        assertEquals(TapZone.PREVIOUS, ImagePages.zone(0.9, ImageMode.PAGES_RIGHT_TO_LEFT))
        assertEquals(TapZone.CONTROLS, ImagePages.zone(0.5, ImageMode.PAGES_RIGHT_TO_LEFT))
    }

    @Test
    fun `dado as laterais do leitor de texto, então o de imagens usa as mesmas larguras`() {
        assertEquals(TapZone.NEXT, ImagePages.zone(TapZones.SIDE - 0.01, ImageMode.PAGES_RIGHT_TO_LEFT))
        assertEquals(TapZone.CONTROLS, ImagePages.zone(TapZones.SIDE, ImageMode.PAGES_RIGHT_TO_LEFT))
        assertEquals(TapZone.CONTROLS, ImagePages.zone(1 - TapZones.SIDE, ImageMode.PAGES_LEFT_TO_RIGHT))
        assertEquals(TapZone.NEXT, ImagePages.zone(1 - TapZones.SIDE + 0.01, ImageMode.PAGES_LEFT_TO_RIGHT))
    }

    @Test
    fun `dado a Tira, então qualquer toque mostra ou esconde os controles`() {
        assertEquals(TapZone.CONTROLS, ImagePages.zone(0.05, ImageMode.STRIP))
        assertEquals(TapZone.CONTROLS, ImagePages.zone(0.95, ImageMode.STRIP))
    }

    @Test
    fun `dado a imagem 3 de 10, então avançar vai à 4 e voltar à 2`() {
        assertEquals(4, ImagePages.turned(3, 10, TapZone.NEXT))
        assertEquals(2, ImagePages.turned(3, 10, TapZone.PREVIOUS))
        assertEquals(3, ImagePages.turned(3, 10, TapZone.CONTROLS))
    }

    @Test
    fun `dado a última imagem, então avançar vai ao bloco de fim, e do bloco não passa`() {
        assertEquals(10, ImagePages.turned(9, 10, TapZone.NEXT))
        assertEquals(10, ImagePages.turned(10, 10, TapZone.NEXT))
    }

    @Test
    fun `dado a primeira imagem, então voltar fica nela`() {
        assertEquals(0, ImagePages.turned(0, 10, TapZone.PREVIOUS))
    }

    @Test
    fun `dado a tela ainda sem tamanho, então a página não é decodificada`() {
        assertEquals(null, ImagePages.sampleSize(800, 1200, 0, 2340))
        assertEquals(null, ImagePages.sampleSize(800, 1200, 1080, 0))
    }

    @Test
    fun `dado uma tira de 1440 por 9350 numa tela de 1080 por 2340, então ela é reduzida até caber na textura`() {
        // Twice its size on screen would be 720 × 4680, above 4096 rows; by 4 it is 360 × 2337.
        assertEquals(4, ImagePages.sampleSize(1440, 9350, 1080, 2340))
    }

    @Test
    fun `dado uma página de 4096 por 4096 numa tela de 1080 por 2340, então ela é reduzida à metade`() {
        assertEquals(2, ImagePages.sampleSize(4096, 4096, 1080, 2340))
    }

    @Test
    fun `dado uma página menor que a tela, então ela é decodificada inteira`() {
        assertEquals(1, ImagePages.sampleSize(800, 1200, 1080, 2340))
    }

    @Test
    fun `dado páginas de muitos tamanhos, então a decodificada fica no dobro da tela e nunca passa de 4096`() {
        val screens = listOf(1080 to 2340, 1440 to 3120, 720 to 1600)
        val pages = listOf(650 to 924, 5000 to 5500, 1440 to 9350, 690 to 30_000, 12_000 to 800, 4096 to 4096)
        for ((screenWidth, screenHeight) in screens) for ((width, height) in pages) {
            val sample = checkNotNull(ImagePages.sampleSize(width, height, screenWidth, screenHeight))
            val fit = minOf(screenWidth.toDouble() / width, screenHeight.toDouble() / height)
            val case = "$width × $height on $screenWidth × $screenHeight, by $sample"
            kotlin.test.assertTrue(width / sample <= StripBands.MAX_DECODED_WIDTH && height / sample <= StripBands.MAX_DECODED_WIDTH, case)
            kotlin.test.assertTrue(width / sample <= maxOf(1.0, 2 * width * fit) + 1 && height / sample <= maxOf(1.0, 2 * height * fit) + 1, case)
        }
    }
}
