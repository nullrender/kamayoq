package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class TapZonesTest {

    @Test
    fun `dado páginas e um toque nos 30 por cento da esquerda, então volta a página`() {
        assertEquals(TapZone.PREVIOUS, TapZones.of(0.1, ReaderFlow.PAGES))
        assertEquals(TapZone.PREVIOUS, TapZones.of(0.29, ReaderFlow.PAGES))
    }

    @Test
    fun `dado páginas e um toque nos 30 por cento da direita, então avança a página`() {
        assertEquals(TapZone.NEXT, TapZones.of(0.71, ReaderFlow.PAGES))
        assertEquals(TapZone.NEXT, TapZones.of(1.0, ReaderFlow.PAGES))
    }

    @Test
    fun `dado páginas e um toque no meio, então mostra ou esconde os controles`() {
        assertEquals(TapZone.CONTROLS, TapZones.of(0.3, ReaderFlow.PAGES))
        assertEquals(TapZone.CONTROLS, TapZones.of(0.5, ReaderFlow.PAGES))
        assertEquals(TapZone.CONTROLS, TapZones.of(0.7, ReaderFlow.PAGES))
    }

    @Test
    fun `dado rolagem, então qualquer toque mostra ou esconde os controles`() {
        assertEquals(TapZone.CONTROLS, TapZones.of(0.05, ReaderFlow.SCROLL))
        assertEquals(TapZone.CONTROLS, TapZones.of(0.95, ReaderFlow.SCROLL))
    }
}
