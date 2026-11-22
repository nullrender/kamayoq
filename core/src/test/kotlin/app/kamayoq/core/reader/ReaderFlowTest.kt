package app.kamayoq.core.reader

import app.kamayoq.core.library.WorkType
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderFlowTest {
    @Test
    fun `dado explorar por toque ativo então o livro abre em rolagem`() {
        assertEquals(ReaderFlow.SCROLL, ReaderFlow.forAccessibility(touchExploration = true))
    }

    @Test
    fun `dado explorar por toque desligado então o livro abre em páginas`() {
        assertEquals(ReaderFlow.PAGES, ReaderFlow.forAccessibility(touchExploration = false))
    }

    @Test
    fun `dado um romance, então ele abre em rolagem`() {
        assertEquals(ReaderFlow.SCROLL, ReaderFlow.of(WorkType.NOVELS, touchExploration = false))
    }

    @Test
    fun `dado um livro, então ele abre em páginas, e em rolagem com explorar por toque`() {
        assertEquals(ReaderFlow.PAGES, ReaderFlow.of(WorkType.BOOKS, touchExploration = false))
        assertEquals(ReaderFlow.SCROLL, ReaderFlow.of(WorkType.BOOKS, touchExploration = true))
    }

    @Test
    fun `dado um romance em que a pessoa escolheu Páginas, então ele abre em páginas`() {
        assertEquals(ReaderFlow.PAGES, ReaderFlow.of(WorkType.NOVELS, touchExploration = false, chosen = ReaderFlow.PAGES))
    }

    @Test
    fun `dado um livro em que a pessoa escolheu Rolar, então ele abre em rolagem`() {
        assertEquals(ReaderFlow.SCROLL, ReaderFlow.of(WorkType.BOOKS, touchExploration = false, chosen = ReaderFlow.SCROLL))
    }

    @Test
    fun `dado Páginas escolhido e explorar por toque ligado, então a obra rola mesmo assim`() {
        assertEquals(ReaderFlow.SCROLL, ReaderFlow.of(WorkType.NOVELS, touchExploration = true, chosen = ReaderFlow.PAGES))
    }
}
