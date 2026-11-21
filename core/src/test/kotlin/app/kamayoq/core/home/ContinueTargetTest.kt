package app.kamayoq.core.home

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinueTargetTest {

    private val markedAt = Instant.parse("2026-11-20T10:00:00Z")
    private val mark = ReadMark(markedAt, ReadMarkSource.PERSON)
    private val unread = ChapterProgress.NEVER_OPENED
    private val read = ChapterProgress(mark, null, 0)

    private fun halfway(at: Instant) = ChapterProgress(null, StripPosition(10, 20, at, "phone-a"), 0)

    private fun chapters(vararg progress: ChapterProgress) = progress.mapIndexed { index, it -> ChapterReading("c${index + 1}", it) }

    @Test
    fun `dado uma obra sem capítulos, então não há para onde continuar`() {
        assertNull(ContinueTargets.of(emptyList(), resumeId = null))
    }

    @Test
    fun `dado uma obra nunca aberta nem marcada, então começa pelo primeiro capítulo`() {
        assertEquals(ContinueTarget("c1", goesOn = false), ContinueTargets.of(chapters(unread, unread), resumeId = null))
    }

    @Test
    fun `dado a leitura parada no meio de um capítulo, então continua nele`() {
        assertEquals(ContinueTarget("c2", goesOn = true), ContinueTargets.of(chapters(read, halfway(markedAt), unread), resumeId = "c2"))
    }

    @Test
    fun `dado capítulos marcados à mão numa obra nunca aberta, então continua no primeiro não lido`() {
        assertEquals(ContinueTarget("c3", goesOn = true), ContinueTargets.of(chapters(read, read, unread), resumeId = null))
    }

    @Test
    fun `dado a leitura parada num capítulo marcado lido depois, então continua no primeiro não lido, e com tudo lido no último`() {
        val stoppedThenMarked = ChapterProgress(mark, StripPosition(10, 20, markedAt.minusSeconds(60), "phone-a"), 0)

        assertEquals(ContinueTarget("c3", goesOn = true), ContinueTargets.of(chapters(read, stoppedThenMarked, unread), resumeId = "c2"))
        assertEquals(ContinueTarget("c2", goesOn = true), ContinueTargets.of(chapters(read, stoppedThenMarked), resumeId = "c2"))
    }

    @Test
    fun `dado um capítulo lido aberto de novo e deixado no meio, então é releitura e continua nele`() {
        val rereading = ChapterProgress(mark, StripPosition(10, 20, markedAt.plusSeconds(60), "phone-a"), 1)

        assertEquals(ContinueTarget("c1", goesOn = true), ContinueTargets.of(chapters(rereading, unread), resumeId = "c1"))
    }
}
