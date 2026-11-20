package app.kamayoq.core.progress

import app.kamayoq.core.library.WorkType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingStatesTest {

    private val at = Instant.parse("2026-11-20T01:00:00Z")
    private val chapter = "chapter-1"

    private fun page(page: Int, reads: Int = 0, mark: ReadMark? = null) =
        ChapterProgress(readMark = mark, position = StripPosition(page, 40, at, "phone-a"), completeReads = reads)

    private val halfway = page(20)
    private val ended = page(40, reads = 1, mark = ReadMark(at, ReadMarkSource.READ_TO_END))

    private fun book(basisPoints: Int, reads: Int = 0) = ChapterProgress(
        readMark = null,
        position = TextPosition(3, 148, "epubcfi(/6/8!/4/2/1:0)", BasisPoints.fromStored(basisPoints), at, "phone-a"),
        completeReads = reads,
    )

    private fun comic(state: ReadingState?, before: ChapterProgress, after: ChapterProgress) =
        ReadingStates.afterSave(state, WorkType.COMICS, chapter, before, after, mark = null)

    private fun bookSave(state: ReadingState?, from: Int, to: Int, mark: StateMark? = null) =
        ReadingStates.afterSave(state, WorkType.BOOKS, chapter, book(from), book(to), mark)

    @Test
    fun `dado uma obra sem estado, quando um capítulo chega ao fim pela leitura, então passa a Lendo`() {
        assertEquals(ReadingState.READING, comic(null, halfway, ended))
    }

    @Test
    fun `dado uma obra em Quero ler, quando um capítulo de novel chega ao fim, então passa a Lendo`() {
        val result = ReadingStates.afterSave(ReadingState.WANT_TO_READ, WorkType.NOVELS, chapter, halfway, ended, mark = null)

        assertEquals(ReadingState.READING, result)
    }

    @Test
    fun `dado uma obra sem estado, quando a leitura anda dentro do capítulo sem chegar ao fim, então o estado fica`() {
        assertNull(comic(null, page(3), halfway))
        assertNull(comic(null, ChapterProgress.NEVER_OPENED, page(0)))
    }

    @Test
    fun `dado uma obra em pausa, quando um capítulo chega ao fim, então volta a Lendo`() {
        assertEquals(ReadingState.READING, comic(ReadingState.ON_HOLD, halfway, ended))
    }

    @Test
    fun `dado uma obra em Lendo, Abandonado ou Lido, quando um capítulo chega ao fim, então o estado fica`() {
        for (state in listOf(ReadingState.READING, ReadingState.DROPPED, ReadingState.COMPLETED)) {
            assertNull(comic(state, halfway, ended), "$state")
        }
    }

    @Test
    fun `dado um capítulo já lido relido até o fim, quando a obra está sem estado, então passa a Lendo`() {
        val again = page(40, reads = 2, mark = ended.readMark)

        assertEquals(ReadingState.READING, comic(null, ended.copy(position = StripPosition(10, 40, at, "phone-a")), again))
    }

    @Test
    fun `dado um capítulo marcado como lido à mão, quando a obra está sem estado, então o estado fica`() {
        val marked = halfway.copy(readMark = ReadMark(at, ReadMarkSource.PERSON))

        assertNull(comic(null, halfway, marked))
    }

    @Test
    fun `dado um capítulo parado no fim, quando a gravação repete o mesmo fim, então o estado fica`() {
        assertNull(comic(null, ended, ended))
    }

    @Test
    fun `dado um livro sem estado, quando passa de 1,99 por cento, então fica, e em 2 por cento passa a Lendo`() {
        assertNull(bookSave(null, from = 150, to = 199))
        assertEquals(ReadingState.READING, bookSave(null, from = 199, to = 200))
    }

    @Test
    fun `dado um livro em Quero ler decidido em 10 por cento, quando chega a 11,99, então fica, e em 12 passa a Lendo`() {
        val mark = StateMark(chapter, BasisPoints.fromStored(1_000))

        assertNull(bookSave(ReadingState.WANT_TO_READ, from = 1_000, to = 1_199, mark = mark))
        assertEquals(ReadingState.READING, bookSave(ReadingState.WANT_TO_READ, from = 1_199, to = 1_200, mark = mark))
    }

    @Test
    fun `dado um livro em pausa decidido em 30 por cento, quando chega a 30,99, então fica, e em 31 volta a Lendo`() {
        val mark = StateMark(chapter, BasisPoints.fromStored(3_000))

        assertNull(bookSave(ReadingState.ON_HOLD, from = 3_000, to = 3_099, mark = mark))
        assertEquals(ReadingState.READING, bookSave(ReadingState.ON_HOLD, from = 3_099, to = 3_100, mark = mark))
    }

    @Test
    fun `dado um livro em pausa com a marca noutro volume, quando lê 1 por cento deste, então volta a Lendo`() {
        val mark = StateMark("chapter-other", BasisPoints.fromStored(9_000))

        assertEquals(ReadingState.READING, bookSave(ReadingState.ON_HOLD, from = 0, to = 100, mark = mark))
    }

    @Test
    fun `dado um livro em pausa, quando a leitura volta para trás da marca, então o estado fica`() {
        val mark = StateMark(chapter, BasisPoints.fromStored(5_000))

        assertNull(bookSave(ReadingState.ON_HOLD, from = 5_000, to = 1_000, mark = mark))
    }

    @Test
    fun `dado um livro abandonado, quando passa de 50 por cento, então o estado fica`() {
        assertNull(bookSave(ReadingState.DROPPED, from = 0, to = 5_000))
    }
}
