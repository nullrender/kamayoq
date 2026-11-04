package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StripBandsTest {

    private val screen = 1080

    @Test
    fun `dado uma imagem com menos que o dobro da largura da tela, então ela não é reduzida`() {
        assertEquals(1, StripBands.sampleSize(imageWidth = 800, screenWidth = screen))
        assertEquals(1, StripBands.sampleSize(imageWidth = 1600, screenWidth = screen))
    }

    @Test
    fun `dado uma imagem bem mais larga que a tela, então reduz pela maior potência de dois que ainda cobre a tela`() {
        assertEquals(2, StripBands.sampleSize(imageWidth = 4000, screenWidth = screen))
        assertEquals(4, StripBands.sampleSize(imageWidth = 4400, screenWidth = screen))
    }

    @Test
    fun `dado uma imagem larga demais para uma textura, então reduz até caber, mesmo ficando mais estreita que a tela`() {
        // 9000 / 2 = 4500 would be over the 4096 limit; 9000 / 4 = 2250 fits.
        assertEquals(4, StripBands.sampleSize(imageWidth = 9000, screenWidth = 4000))
    }

    @Test
    fun `dado uma página de manhwa muito alta, então as faixas cobrem tudo, sem buraco nem sobreposição`() {
        val bands = StripBands.bands(imageWidth = 800, imageHeight = 30_000, sampleSize = 1)

        assertEquals(0, bands.first().first)
        assertEquals(29_999, bands.last().last)
        bands.zipWithNext().forEach { (upper, lower) -> assertEquals(upper.last + 1, lower.first) }
    }

    @Test
    fun `dado uma página muito alta, então nenhuma faixa decodificada passa do limite de memória`() {
        listOf(1 to 800, 2 to 4000, 4 to 9000).forEach { (sample, width) ->
            StripBands.bands(imageWidth = width, imageHeight = 50_000, sampleSize = sample).forEach { band ->
                val decodedWidth = (width + sample - 1) / sample
                val decodedHeight = (band.count() + sample - 1) / sample
                assertTrue(decodedWidth * decodedHeight * 4 <= StripBands.MAX_BAND_BYTES, "band $band at $sample")
            }
        }
    }

    @Test
    fun `dado uma página reduzida, então cada faixa começa num múltiplo da redução, para não deixar emenda`() {
        val bands = StripBands.bands(imageWidth = 4000, imageHeight = 20_001, sampleSize = 2)

        bands.drop(1).forEach { assertEquals(0, it.first % 2, "band $it") }
    }

    @Test
    fun `dado uma página pequena, então ela é uma faixa só`() {
        assertEquals(listOf(0..1199), StripBands.bands(imageWidth = 800, imageHeight = 1200, sampleSize = 1))
    }

    @Test
    fun `dado o tamanho da página, então a altura na tela é proporcional à largura da tela`() {
        assertEquals(13_500, StripBands.displayHeight(imageWidth = 800, imageHeight = 10_000, screenWidth = screen))
        assertEquals(540, StripBands.displayHeight(imageWidth = 2000, imageHeight = 1000, screenWidth = screen))
    }

    @Test
    fun `dado um tamanho de imagem impossível, então é recusado`() {
        assertFailsWith<IllegalArgumentException> { StripBands.sampleSize(imageWidth = 0, screenWidth = screen) }
        assertFailsWith<IllegalArgumentException> { StripBands.bands(imageWidth = 800, imageHeight = 0, sampleSize = 1) }
        assertFailsWith<IllegalArgumentException> { StripBands.bands(imageWidth = 800, imageHeight = 100, sampleSize = 3) }
        assertFailsWith<IllegalArgumentException> { StripBands.displayHeight(imageWidth = 0, imageHeight = 10, screenWidth = 1) }
    }
}
