package app.kamayoq.core.progress

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StripReadingTest {

    private val device = "phone-a"
    private val earlier = Instant.parse("2026-10-20T22:00:00Z")
    private val now = Instant.parse("2026-10-25T14:00:00Z")

    private fun at(page: Int, total: Int = TOTAL) = StripPosition(page, total, earlier, device)

    private fun open(saved: ChapterProgress, total: Int = TOTAL) = assertNotNull(StripReading.open(saved, total, device))

    /** Shows each page in order, one second apart, and returns the last progress. */
    private fun StripReading.read(vararg pages: Int): ChapterProgress {
        var last = progress
        pages.forEachIndexed { i, page -> last = assertNotNull(show(page, now.plusSeconds(i.toLong()))) }
        return last
    }

    @Test
    fun `dado um capítulo nunca aberto, quando abre, então começa na primeira imagem`() {
        assertEquals(0, open(ChapterProgress.NEVER_OPENED).startPage)
    }

    @Test
    fun `dado um capítulo não lido parado na imagem 12, quando abre, então começa na imagem 12`() {
        val saved = ChapterProgress(readMark = null, position = at(12), completeReads = 0)

        assertEquals(12, open(saved).startPage)
    }

    @Test
    fun `dado um capítulo lido cuja última leitura chegou ao bloco de fim, quando abre, então começa na primeira imagem`() {
        val saved = ChapterProgress(ReadMark(earlier, ReadMarkSource.READ_TO_END), at(TOTAL), completeReads = 1)

        assertEquals(0, open(saved).startPage)
    }

    @Test
    fun `dado um capítulo lido com a releitura parada na imagem 5, quando abre, então começa na imagem 5`() {
        val saved = ChapterProgress(ReadMark(earlier, ReadMarkSource.READ_TO_END), at(5), completeReads = 1)

        assertEquals(5, open(saved).startPage)
    }

    @Test
    fun `dado um capítulo que chegou ao bloco de fim e voltou à imagem 30, quando abre de novo, então começa na imagem 30`() {
        val reading = open(ChapterProgress.NEVER_OPENED)
        val afterClosing = reading.read(37, 38, 39, TOTAL, 39, 30)

        assertEquals(30, open(afterClosing).startPage)
    }

    @Test
    fun `dado o total de imagens diferente do guardado, quando abre, então começa na primeira imagem`() {
        // The file changed; matching the old progress to the new file is rule E1, for the import.
        val saved = ChapterProgress(readMark = null, position = at(12, total = 20), completeReads = 0)

        assertEquals(0, open(saved, total = TOTAL).startPage)
    }

    @Test
    fun `dado um capítulo sem imagens, quando abre, então é recusado`() {
        assertNull(StripReading.open(ChapterProgress.NEVER_OPENED, totalPages = 0, device = device))
    }

    @Test
    fun `dado a leitura passando de imagem em imagem, então cada troca devolve a posição para gravar, com hora e aparelho`() {
        val reading = open(ChapterProgress.NEVER_OPENED)

        assertEquals(
            ChapterProgress(readMark = null, position = StripPosition(1, TOTAL, now, device), completeReads = 0),
            reading.show(1, now),
        )
        assertEquals(
            StripPosition(2, TOTAL, now.plusSeconds(3), device),
            reading.show(2, now.plusSeconds(3))?.position,
        )
        assertEquals(StripPosition(2, TOTAL, now.plusSeconds(3), device), reading.progress.position)
    }

    @Test
    fun `dado a mesma imagem mostrada de novo, então nada muda`() {
        val reading = open(ChapterProgress.NEVER_OPENED)
        val first = reading.show(4, now)

        assertEquals(first, reading.show(4, now.plusSeconds(10)))
    }

    @Test
    fun `dado a última imagem na tela, quando o bloco de fim ainda não apareceu, então o capítulo continua não lido`() {
        val progress = open(ChapterProgress.NEVER_OPENED).read(0, 1, TOTAL - 1)

        assertNull(progress.readMark)
        assertEquals(0, progress.completeReads)
        assertEquals(TOTAL - 1, progress.position?.page)
    }

    @Test
    fun `dado o bloco de fim na tela, então o capítulo fica lido por leitura até o fim e as leituras completas somam 1`() {
        val reading = open(ChapterProgress.NEVER_OPENED)
        reading.read(0, 1, TOTAL - 1)
        val end = now.plusSeconds(60)

        val progress = assertNotNull(reading.show(TOTAL, end))

        assertEquals(ReadMark(end, ReadMarkSource.READ_TO_END), progress.readMark)
        assertEquals(1, progress.completeReads)
        assertEquals(StripPosition(TOTAL, TOTAL, end, device), progress.position)
    }

    @Test
    fun `dado o bloco de fim já alcançado nesta abertura, quando a pessoa sobe e desce de novo, então as leituras completas não somam outra vez`() {
        val progress = open(ChapterProgress.NEVER_OPENED).read(TOTAL - 1, TOTAL, TOTAL - 1, TOTAL)

        assertEquals(1, progress.completeReads)
        assertEquals(now.plusSeconds(1), progress.readMark?.at)
    }

    @Test
    fun `dado um capítulo lido em outra abertura, quando a releitura chega ao bloco de fim, então soma 1 e a marca de lido não muda`() {
        val mark = ReadMark(earlier, ReadMarkSource.READ_TO_END)
        val saved = ChapterProgress(mark, at(TOTAL), completeReads = 1)

        val progress = open(saved).read(0, 1, TOTAL)

        assertEquals(mark, progress.readMark)
        assertEquals(2, progress.completeReads)
    }

    @Test
    fun `dado a última leitura parada no bloco de fim, quando a nova abertura mostra o bloco de fim de cara, então soma 1 uma vez só`() {
        // A short chapter can show its end block first.
        val mark = ReadMark(earlier, ReadMarkSource.READ_TO_END)
        val saved = ChapterProgress(mark, at(TOTAL), completeReads = 1)

        val reading = open(saved)

        assertEquals(2, reading.show(TOTAL, now)?.completeReads)
        val progress = reading.read(TOTAL - 1, TOTAL)
        assertEquals(2, progress.completeReads)
        assertEquals(mark, progress.readMark)
    }

    @Test
    fun `dado um capítulo marcado lido pela pessoa, quando a leitura passa pelo meio, então continua lido com a mesma marca`() {
        val mark = ReadMark(earlier, ReadMarkSource.PERSON)
        val saved = ChapterProgress(mark, position = null, completeReads = 0)

        val progress = open(saved).read(0, 1, 2)

        assertEquals(mark, progress.readMark)
        assertEquals(0, progress.completeReads)
        assertEquals(2, progress.position?.page)
    }

    @Test
    fun `dado uma imagem fora do capítulo, então é recusada e nada muda`() {
        val reading = open(ChapterProgress.NEVER_OPENED)
        val before = reading.show(3, now)

        assertNull(reading.show(-1, now.plusSeconds(1)))
        assertNull(reading.show(TOTAL + 1, now.plusSeconds(2)))
        assertEquals(before, reading.progress)
    }

    @Test
    fun `dado uma posição fora do capítulo, quando criada, então é recusada`() {
        // An impossible position must not exist to be saved.
        assertFailsWith<IllegalArgumentException> { StripPosition(0, 0, now, device) }
        assertFailsWith<IllegalArgumentException> { StripPosition(-1, TOTAL, now, device) }
        assertFailsWith<IllegalArgumentException> { StripPosition(TOTAL + 1, TOTAL, now, device) }
    }

    @Test
    fun `dado leituras completas negativas, quando o progresso é criado, então é recusado`() {
        assertFailsWith<IllegalArgumentException> {
            ChapterProgress(readMark = null, position = null, completeReads = -1)
        }
    }

    @Test
    fun `dado o bloco de fim de um capítulo de uma imagem, quando criado, então vale`() {
        assertEquals(true, StripPosition(1, 1, now, device).isAtEnd)
    }

    private companion object {
        /** Images in the test chapter; page TOTAL is the end-of-chapter block. */
        const val TOTAL = 40
    }

    @Test
    fun `dado uma posição do Kavita com outra contagem de páginas, quando abro, então fica na mesma página`() {
        val saved = ChapterProgress(null, StripPosition(2, 4, java.time.Instant.EPOCH, "kavita"), 0)

        assertEquals(2, StripReading.open(saved, totalPages = 6, device = "phone-a")?.startPage)
    }

    @Test
    fun `dado uma posição do Kavita além das imagens deste arquivo, quando abro, então fica na última imagem`() {
        val saved = ChapterProgress(null, StripPosition(7, 9, java.time.Instant.EPOCH, "kavita"), 0)

        assertEquals(5, StripReading.open(saved, totalPages = 6, device = "phone-a")?.startPage)
    }
}
