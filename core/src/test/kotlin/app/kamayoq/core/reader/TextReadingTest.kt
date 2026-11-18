package app.kamayoq.core.reader

import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TextReadingTest {

    private val device = "phone-a"
    private val earlier = Instant.parse("2026-10-20T22:00:00Z")
    private val now = Instant.parse("2026-11-03T14:00:00Z")
    private val saved = TextPosition(1, 3, "epubcfi(/6/4!/4/20)", fraction(0.4), earlier, device)

    private fun fraction(value: Double) = assertNotNull(BasisPoints.fromFraction(value))

    private fun moved(reason: RelocateReason, cfi: String = "epubcfi(/6/4!/4/2)") =
        ReaderMessage.Relocated(1, 3, cfi, fraction(0.1), reason)

    @Test
    fun `dado uma posição salva, quando abre, então o leitor parte do CFI salvo`() {
        assertEquals("epubcfi(/6/4!/4/20)", TextReading(saved, device).start)
    }

    @Test
    fun `dado uma posição salva, quando chega o primeiro relocated antes da restauração, então a posição salva não é sobrescrita`() {
        val reading = TextReading(saved, device)

        // The fork reports the first page it lays out, and the restore itself, before init ends.
        assertNull(reading.relocated(moved(RelocateReason.PAGE), now))
        assertNull(reading.relocated(moved(RelocateReason.NAVIGATION), now))

        assertEquals(saved, reading.position)
        assertEquals(TextReading.Phase.OPENING, reading.phase)
    }

    @Test
    fun `dado a restauração confirmada, quando o paginator se reajusta sozinho, então a âncora continua a salva`() {
        val reading = TextReading(saved, device)
        reading.restored()

        // A new font or a turned screen re-lays the book out: that is not the person moving.
        assertNull(reading.relocated(moved(RelocateReason.ANCHOR), now))
        assertNull(reading.relocated(moved(RelocateReason.CONTAINER_SCROLL), now))

        assertEquals(saved, reading.position)
        assertEquals(TextReading.Phase.RESTORED, reading.phase)
    }

    @Test
    fun `dado a restauração confirmada, quando a pessoa vira a página, então a posição nova é aceita com data e aparelho`() {
        val reading = TextReading(saved, device)
        reading.restored()

        val position = reading.relocated(moved(RelocateReason.SNAP, cfi = "epubcfi(/6/4!/4/24)"), now)

        assertEquals(TextPosition(1, 3, "epubcfi(/6/4!/4/24)", fraction(0.1), now, device), position)
        assertEquals(position, reading.position)
        assertEquals(TextReading.Phase.READING, reading.phase)
    }

    @Test
    fun `dado a pessoa já lendo, quando o paginator se reajusta, então a âncora fica onde a pessoa deixou`() {
        val reading = TextReading(saved, device)
        reading.restored()
        val left = reading.relocated(moved(RelocateReason.PAGE, cfi = "epubcfi(/6/4!/4/24)"), now)

        assertNull(reading.relocated(moved(RelocateReason.ANCHOR, cfi = "epubcfi(/6/4!/4/22)"), now.plusSeconds(1)))
        reading.restored()

        assertEquals(left, reading.position)
        assertEquals(TextReading.Phase.READING, reading.phase)
    }

    @Test
    fun `dado um livro nunca aberto, quando abre, então parte do início e nada é gravado até a pessoa se mover`() {
        val reading = TextReading(saved = null, device)
        reading.restored()

        assertNull(reading.start)
        assertNull(reading.relocated(moved(RelocateReason.ANCHOR), now))
        assertNull(reading.position)
        assertNotNull(reading.relocated(moved(RelocateReason.SCROLL), now))
    }

    // Piece 23, decision 9: only the end block marks a book read; the fraction never decides it.
    private fun readingAtLastPage(saved: ChapterProgress = ChapterProgress(null, this.saved, 0)): TextReading {
        val reading = TextReading(saved, device)
        reading.restored()
        reading.relocated(ReaderMessage.Relocated(2, 3, "epubcfi(/6/8!/4/90)", BasisPoints.fromStored(BasisPoints.END), RelocateReason.PAGE), now)
        return reading
    }

    @Test
    fun `dado 10000 pontos-base sem o bloco de fim, então o livro não fica lido`() {
        val reading = readingAtLastPage()

        assertNull(reading.progress.readMark)
        assertEquals(0, reading.progress.completeReads)
    }

    @Test
    fun `dado o bloco de fim à vista, então o livro fica lido e as leituras somam 1`() {
        val reading = readingAtLastPage()

        val progress = reading.ended(now.plusSeconds(5))

        assertEquals(ReadMark(now.plusSeconds(5), ReadMarkSource.READ_TO_END), progress?.readMark)
        assertEquals(1, progress?.completeReads)
        assertEquals("epubcfi(/6/8!/4/90)", progress?.position?.let { it as TextPosition }?.cfi)
        assertEquals(progress, reading.progress)
    }

    @Test
    fun `dado voltar e avançar no fim, então não soma de novo`() {
        val reading = readingAtLastPage()
        reading.ended(now.plusSeconds(5))
        // Previous page closes the block on the same page; no move reaches the guard.

        assertNull(reading.ended(now.plusSeconds(9)))
        assertEquals(1, reading.progress.completeReads)
    }

    @Test
    fun `dado um livro já lido, quando chega ao fim de novo, então a primeira marca fica e as leituras somam`() {
        val first = ReadMark(earlier, ReadMarkSource.READ_TO_END)
        val reading = readingAtLastPage(ChapterProgress(first, saved, 1))

        val progress = reading.ended(now.plusSeconds(5))

        assertEquals(first, progress?.readMark)
        assertEquals(2, progress?.completeReads)
    }

    @Test
    fun `dado o fim pedido antes da restauração, então nada conta`() {
        val reading = TextReading(saved, device)

        assertNull(reading.ended(now))
        assertEquals(0, reading.progress.completeReads)
    }

    @Test
    fun `dado uma seção fora do livro ou algo que não é CFI, então a posição de texto é recusada`() {
        assertFailsWith<IllegalArgumentException> { TextPosition(3, 3, "epubcfi(/6/4)", fraction(0.0), now, device) }
        assertFailsWith<IllegalArgumentException> { TextPosition(0, 1, "/6/4", fraction(0.0), now, device) }
    }
}
