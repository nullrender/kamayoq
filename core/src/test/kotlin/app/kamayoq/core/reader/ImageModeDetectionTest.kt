package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

// The cases are numbers from the research of 22/11/2026 (kamayoq-notes, "image-mode-detection"): the
// pages are sizes, not images.
class ImageModeDetectionTest {

    private fun pages(count: Int, width: Int, height: Int) = List<ImageSize?>(count) { ImageSize(width, height) }

    private fun detect(sizes: List<ImageSize?>, vararg words: String, manga: MangaField? = null) =
        ImageModeDetection.detect(ImageSignals(sizes, words.toList(), manga))

    private val strip = ImageMode.STRIP
    private val leftToRight = ImageMode.PAGES_LEFT_TO_RIGHT
    private val rightToLeft = ImageMode.PAGES_RIGHT_TO_LEFT

    /** A scanned chapter: widths that differ a little from page to page. */
    private val scanned = listOf(1000, 1012, 990, 1100, 1040, 980, 1005, 1060).map { ImageSize(it, (it * 1.45).toInt()) }

    @Test
    fun `dado as fatias de um manhwa de 690 por 1300, então é Tira pelas imagens altas`() {
        assertEquals(Detection(strip, DetectionStep.TALL_IMAGES), detect(pages(20, 690, 1300)))
    }

    @Test
    fun `dado imagens de 720 por 1271 como as de I Shall Master This Family, então é Tira pelas imagens altas`() {
        // 1,77: acima do limite de 1,7, abaixo do 1,8 que o relatório usava.
        assertEquals(Detection(strip, DetectionStep.TALL_IMAGES), detect(pages(80, 720, 1271)))
    }

    @Test
    fun `dado os pedaços do corte do Mihon no S24, então é Tira pelas imagens altas`() {
        val pieces = listOf(ImageSize(1080, 2340), ImageSize(1080, 3510), ImageSize(1080, 4680), ImageSize(1080, 2600))
        assertEquals(Detection(strip, DetectionStep.TALL_IMAGES), detect(pieces))
    }

    @Test
    fun `dado imagens altas e o gênero Manga, então as imagens altas ganham e é Tira`() {
        assertEquals(Detection(strip, DetectionStep.TALL_IMAGES), detect(pages(20, 690, 1300), "Manga", manga = MangaField.YES_AND_RIGHT_TO_LEFT))
    }

    @Test
    fun `dado menos da metade da área em imagens altas, então as imagens altas não decidem`() {
        val mostlyPages = pages(6, 650, 924) + pages(1, 690, 1300)
        assertEquals(DetectionStep.NOTHING_DECIDED, detect(mostlyPages).step)
    }

    @Test
    fun `dado as fatias de 800 por 1280 do WEBTOON com a etiqueta Webtoon, então é Tira pela etiqueta`() {
        assertEquals(Detection(strip, DetectionStep.FORMAT_TAG), detect(pages(30, 800, 1280), "Romance", "Webtoon"))
    }

    @Test
    fun `dado Long Strip, Webtoons e Vertical em qualquer caixa, então cada um é etiqueta de formato`() {
        for (tag in listOf("Long Strip", "webtoons", "VERTICAL", " long strip ")) {
            assertEquals(DetectionStep.FORMAT_TAG, detect(pages(30, 800, 1280), tag).step, tag)
        }
    }

    @Test
    fun `dado a etiqueta Long Strip e o Manga da direita para a esquerda, então a etiqueta ganha e é Tira`() {
        assertEquals(Detection(strip, DetectionStep.FORMAT_TAG), detect(pages(30, 800, 1280), "Long Strip", manga = MangaField.YES_AND_RIGHT_TO_LEFT))
    }

    @Test
    fun `dado as fatias de 800 por 1280 sem nada que decida, então é Tira`() {
        assertEquals(Detection(strip, DetectionStep.NOTHING_DECIDED), detect(pages(30, 800, 1280)))
    }

    @Test
    fun `dado um mangá de 650 por 924 com largura fixa e sem metadado, então nada decide e abre na Tira`() {
        assertEquals(Detection(strip, DetectionStep.NOTHING_DECIDED), detect(pages(20, 650, 924)))
    }

    @Test
    fun `dado o mesmo mangá com o gênero Japanese, então é Páginas para a esquerda pelo metadado`() {
        assertEquals(Detection(rightToLeft, DetectionStep.MANGA_METADATA), detect(pages(20, 650, 924), "Action", "Japanese"))
    }

    @Test
    fun `dado o Manga da direita para a esquerda, então é Páginas para a esquerda pelo metadado`() {
        assertEquals(Detection(rightToLeft, DetectionStep.MANGA_METADATA), detect(pages(20, 650, 924), manga = MangaField.YES_AND_RIGHT_TO_LEFT))
    }

    @Test
    fun `dado Manga Yes, como nas cópias em inglês de Teto x Egen, então ele não conta como mangá`() {
        assertEquals(Detection(strip, DetectionStep.NOTHING_DECIDED), detect(pages(20, 650, 924), manga = MangaField.YES))
    }

    @Test
    fun `dado um capítulo escaneado com larguras variando, então é Páginas para a direita sem mais nada`() {
        assertEquals(Detection(leftToRight, DetectionStep.VARYING_WIDTHS), detect(scanned))
    }

    @Test
    fun `dado uma tira japonesa de larguras variando como a Waka-chan, com a etiqueta Long Strip, então a etiqueta vem antes e é Tira`() {
        val wakaChan = listOf(700, 720, 690, 710, 705, 730, 698).map { ImageSize(it, (it * 1.5).toInt()) }
        assertEquals(DetectionStep.VARYING_WIDTHS, detect(wakaChan).step)
        assertEquals(Detection(strip, DetectionStep.FORMAT_TAG), detect(wakaChan, "Long Strip"))
    }

    @Test
    fun `dado capa e créditos de outra largura e o miolo igual, então a largura não decide`() {
        val withCover = listOf(ImageSize(1400, 2000)) + pages(6, 650, 924) + listOf(ImageSize(900, 900))
        assertEquals(DetectionStep.NOTHING_DECIDED, detect(withCover).step)
    }

    @Test
    fun `dado menos de quatro imagens no miolo, então a largura não decide`() {
        assertEquals(DetectionStep.NOTHING_DECIDED, detect(scanned.take(5)).step)
    }

    @Test
    fun `dado Páginas pelo Manga, então o ComicInfo ganha das palavras na direção`() {
        assertEquals(leftToRight, detect(scanned, "Japanese", manga = MangaField.NO).mode)
        assertEquals(rightToLeft, detect(scanned, "Korean", manga = MangaField.YES_AND_RIGHT_TO_LEFT).mode)
    }

    @Test
    fun `dado Páginas, então Coreano, Chinês e Manhwa dão para a direita e Mangá e Japonês para a esquerda`() {
        for (word in listOf("Coreano", "Chinês", "Manhwa", "Manhua", "English", "Comic", "Inglês")) {
            assertEquals(leftToRight, detect(scanned, word).mode, word)
        }
        for (word in listOf("Mangá", "Japonês", "MANGA")) {
            assertEquals(rightToLeft, detect(scanned, word).mode, word)
        }
    }

    @Test
    fun `dado palavras que se contradizem, então elas se anulam e a direção fica para a direita`() {
        assertEquals(leftToRight, detect(scanned, "Japanese", "Korean").mode)
    }

    @Test
    fun `dado palavras que só contêm mangá ou que vêm com erro, então elas não contam`() {
        assertEquals(DetectionStep.NOTHING_DECIDED, detect(pages(20, 650, 924), "Contém manga", "Manwha", "漫画").step)
    }

    @Test
    fun `dado imagens de tamanho desconhecido, então elas ficam fora da conta`() {
        val withUnknown = pages(10, 690, 1300) + List<ImageSize?>(30) { null }
        assertEquals(Detection(strip, DetectionStep.TALL_IMAGES), detect(withUnknown))
        assertEquals(Detection(strip, DetectionStep.NOTHING_DECIDED), detect(List(5) { null }))
    }
}
