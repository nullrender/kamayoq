package app.kamayoq.core.reader

import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Step 4, piece B7: reading crosses the chapters of a split file with no reload
// (docs/decisions/novel-chapters-2026-11-18.md, section 3).
class SplitReadingTest {
    private val now = Instant.parse("2026-11-18T22:00:00Z")
    private val later = Instant.parse("2026-11-18T22:05:00Z")

    private fun never(count: Int) = List(count) { ChapterProgress.NEVER_OPENED }

    private fun page(chapter: Int?, fraction: Int, reason: RelocateReason = RelocateReason.PAGE, cfi: String = "epubcfi(/6/4!/4/$fraction)") =
        ReaderMessage.Relocated(
            section = 1, sections = 4, cfi = cfi, position = BasisPoints.fromStored(5_000), reason = reason,
            chapter = chapter, chapterPosition = BasisPoints.fromStored(fraction),
        )

    private fun reading(saved: List<ChapterProgress>, opened: Int) = SplitReading(saved, opened, "phone-a").apply { restored() }

    private fun TextPosition?.points() = this?.fraction?.value

    private fun ChapterProgress.place() = position as? TextPosition

    @Test
    fun `dado a leitura do capítulo 3, quando viro a página dentro dele, então só ele ganha o lugar, com a fração do capítulo`() {
        val reading = reading(never(5), opened = 2)

        val saves = reading.relocated(page(chapter = 2, fraction = 2_500), now)

        assertEquals(listOf(2), saves.map { it.chapter })
        val place = saves.single().progress.place()
        assertEquals(2_500, place.points())
        assertEquals("epubcfi(/6/4!/4/2500)", place?.cfi)
        assertNull(saves.single().progress.readMark)
        assertEquals(2, reading.current)
    }

    @Test
    fun `dado a leitura do capítulo 3, quando viro a página para dentro do 4, então o 3 fica lido no fim e a obra passa a reabrir no 4`() {
        val reading = reading(never(5), opened = 2)
        reading.relocated(page(chapter = 2, fraction = 9_000), now)

        val saves = reading.relocated(page(chapter = 3, fraction = 300, cfi = "epubcfi(/6/6!/4/2)"), later)

        assertEquals(listOf(2, 3), saves.map { it.chapter })
        val left = saves[0].progress
        assertEquals(ReadMark(later, ReadMarkSource.READ_TO_END), left.readMark)
        assertEquals(1, left.completeReads)
        assertEquals(BasisPoints.END, left.place().points())
        assertEquals("epubcfi(/6/6!/4/2)", left.place()?.cfi)
        assertNull(saves[1].progress.readMark)
        assertEquals(300, saves[1].progress.place().points())
        assertEquals(3, reading.current)
    }

    @Test
    fun `dado a leitura do capítulo 3, quando rolo de uma vez até o 5, então o 3 e o 4 ficam lidos`() {
        val reading = reading(never(5), opened = 2)

        val saves = reading.relocated(page(chapter = 4, fraction = 100, reason = RelocateReason.SCROLL), now)

        assertEquals(listOf(2, 3, 4), saves.map { it.chapter })
        assertEquals(listOf(true, true, false), saves.map { it.progress.readMark != null })
    }

    @Test
    fun `dado a leitura do capítulo 3, quando salto pelo sumário para o 10, então o 3 não fica lido`() {
        val reading = reading(never(12), opened = 2)
        reading.relocated(page(chapter = 2, fraction = 4_000), now)

        val saves = reading.relocated(page(chapter = 9, fraction = 0, reason = RelocateReason.NAVIGATION), later)

        assertEquals(listOf(9), saves.map { it.chapter })
        assertNull(reading.progressOf(2).readMark)
        assertEquals(4_000, reading.progressOf(2).place().points())
        assertEquals(9, reading.current)
    }

    @Test
    fun `dado um salto pelo sumário para o 10, quando leio adiante até o 11, então o 10 fica lido`() {
        val reading = reading(never(12), opened = 2)
        reading.relocated(page(chapter = 9, fraction = 0, reason = RelocateReason.NAVIGATION), now)

        val saves = reading.relocated(page(chapter = 10, fraction = 200), later)

        assertEquals(listOf(9, 10), saves.map { it.chapter })
        assertEquals(ReadMarkSource.READ_TO_END, saves[0].progress.readMark?.source)
    }

    @Test
    fun `dado a leitura do capítulo 3, quando volto uma página para o fim do 2 e avanço de novo, então o 2 não fica lido`() {
        val reading = reading(never(5), opened = 2)

        reading.relocated(page(chapter = 1, fraction = 9_800), now)
        val saves = reading.relocated(page(chapter = 2, fraction = 300), later)

        assertEquals(listOf(2), saves.map { it.chapter })
        assertNull(reading.progressOf(1).readMark)
        assertEquals(9_800, reading.progressOf(1).place().points())
    }

    @Test
    fun `dado um capítulo já lido, quando passo por ele de novo, então a primeira marca fica e as leituras somam uma vez por abertura`() {
        val first = ReadMark(Instant.parse("2026-11-01T10:00:00Z"), ReadMarkSource.PERSON)
        val saved = never(5).toMutableList().also { it[2] = ChapterProgress(first, null, completeReads = 1) }
        val reading = reading(saved, opened = 2)

        reading.relocated(page(chapter = 3, fraction = 100), now)
        reading.relocated(page(chapter = 2, fraction = 100, reason = RelocateReason.NAVIGATION), now)
        val again = reading.relocated(page(chapter = 3, fraction = 100), later)

        assertEquals(first, reading.progressOf(2).readMark)
        assertEquals(2, reading.progressOf(2).completeReads)
        assertEquals(listOf(2, 3), again.map { it.chapter })
    }

    @Test
    fun `dado o último capítulo do arquivo, quando o bloco de fim aparece, então ele fica lido no fim, uma vez`() {
        val reading = reading(never(3), opened = 2)
        reading.relocated(page(chapter = 2, fraction = 9_900), now)

        val saved = reading.ended(later)

        assertEquals(2, saved?.chapter)
        assertEquals(ReadMark(later, ReadMarkSource.READ_TO_END), saved?.progress?.readMark)
        assertEquals(1, saved?.progress?.completeReads)
        assertEquals(BasisPoints.END, saved?.progress?.place().points())
        assertNull(reading.ended(later))
    }

    @Test
    fun `dado que a página ainda não confirmou a abertura, quando ela relata posições ou o fim, então nada é gravado`() {
        val reading = SplitReading(never(3), opened = 1, device = "phone-a")

        assertEquals(emptyList(), reading.relocated(page(chapter = 2, fraction = 100), now))
        assertNull(reading.ended(now))
        assertEquals(1, reading.current)
    }

    @Test
    fun `dado a abertura confirmada, quando a página se rearruma sozinha, então nada é gravado`() {
        val reading = reading(never(3), opened = 1)

        assertEquals(emptyList(), reading.relocated(page(chapter = 2, fraction = 100, reason = RelocateReason.ANCHOR), now))
    }

    @Test
    fun `dado um capítulo nunca aberto, então ele abre pelo começo, e um parado no meio abre no trecho guardado`() {
        val middle = ChapterProgress(null, TextPosition(1, 4, "epubcfi(/6/4!/4/40)", BasisPoints.fromStored(4_000), now, "phone-a"), 0)
        val saved = listOf(ChapterProgress.NEVER_OPENED, middle)

        assertNull(SplitReading(saved, opened = 0, device = "phone-a").start)
        assertEquals("epubcfi(/6/4!/4/40)", SplitReading(saved, opened = 1, device = "phone-a").start)
        assertEquals(1, SplitReading(saved, opened = 1, device = "phone-a").startSection)
    }

    @Test
    fun `dado um capítulo cuja última leitura chegou ao fim, então ele abre de novo pelo começo`() {
        val finished = ChapterProgress(
            ReadMark(now, ReadMarkSource.READ_TO_END),
            TextPosition(2, 4, "epubcfi(/6/6!/4/2)", BasisPoints.fromStored(BasisPoints.END), now, "phone-a"),
            completeReads = 1,
        )

        assertNull(SplitReading(listOf(finished, ChapterProgress.NEVER_OPENED), opened = 0, device = "phone-a").start)
    }

    @Test
    fun `dado uma página que não diz o capítulo, então o lugar fica no capítulo que estava sendo lido`() {
        val reading = reading(never(3), opened = 1)

        val saves = reading.relocated(page(chapter = null, fraction = 700), now)

        assertEquals(listOf(1), saves.map { it.chapter })
    }

    @Test
    fun `dado um arquivo cuja fração de capítulo a página não mede, então o lugar é guardado com a fração no começo`() {
        val reading = reading(never(3), opened = 1)
        val unmeasured = ReaderMessage.Relocated(1, 4, "epubcfi(/6/4!/4/8)", BasisPoints.fromStored(5_000), RelocateReason.PAGE, chapter = 1)

        val saves = reading.relocated(unmeasured, now)

        assertEquals(BasisPoints.START, saves.single().progress.place().points())
        assertEquals("epubcfi(/6/4!/4/8)", saves.single().progress.place()?.cfi)
    }
}
