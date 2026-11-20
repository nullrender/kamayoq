package app.kamayoq.core.progress

import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.WorkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The "finish" rule of the progress contract, section 5, with books left out (his decision of 20/11/2026).
class ReadingStatesFinishTest {
    private val completed = Publication(PublicationStatus.COMPLETED, 0)

    private fun finish(
        state: ReadingState? = ReadingState.READING,
        type: WorkType = WorkType.COMICS,
        publication: Publication = completed,
        finishedLast: Boolean = true,
        everyChapterRead: Boolean = true,
    ) = ReadingStates.afterFinishing(state, type, publication, finishedLast, everyChapterRead)

    @Test
    fun `dado um quadrinho em Lendo com publicação concluída e nada faltando, quando o último capítulo termina com tudo lido, então vai para Lido`() {
        assertEquals(ReadingState.COMPLETED, finish())
    }

    @Test
    fun `dado uma novel sem estado nas mesmas condições, então também vai para Lido`() {
        assertEquals(ReadingState.COMPLETED, finish(state = null, type = WorkType.NOVELS))
    }

    @Test
    fun `dado uma obra em Abandonado ou Em pausa, quando termina, então vai para Lido, porque a regra vale para todo estado menos Lido`() {
        assertEquals(ReadingState.COMPLETED, finish(state = ReadingState.DROPPED))
        assertEquals(ReadingState.COMPLETED, finish(state = ReadingState.ON_HOLD))
    }

    @Test
    fun `dado uma obra já em Lido, então nada muda`() {
        assertNull(finish(state = ReadingState.COMPLETED))
    }

    @Test
    fun `dado um livro terminado com publicação concluída, então não vai para Lido sozinho`() {
        assertNull(finish(type = WorkType.BOOKS))
    }

    @Test
    fun `dado a publicação em andamento, em hiato, cancelada ou desconhecida, então fica como está`() {
        for (status in listOf(PublicationStatus.ONGOING, PublicationStatus.HIATUS, PublicationStatus.CANCELLED, null)) {
            assertNull(finish(publication = Publication(status, 0)), "status $status")
        }
    }

    @Test
    fun `dado a publicação concluída com capítulos faltando na origem ou com o que falta desconhecido, então fica como está`() {
        assertNull(finish(publication = Publication(PublicationStatus.COMPLETED, 9)))
        assertNull(finish(publication = Publication(PublicationStatus.COMPLETED, null)))
    }

    @Test
    fun `dado o último capítulo terminado mas um anterior ainda não lido, então fica como está`() {
        assertNull(finish(everyChapterRead = false))
    }

    @Test
    fun `dado tudo lido mas um evento que não terminou o último capítulo, então fica como está`() {
        assertNull(finish(finishedLast = false))
    }
}
