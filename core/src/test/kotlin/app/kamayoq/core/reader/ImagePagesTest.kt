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
}
