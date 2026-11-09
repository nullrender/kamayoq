package app.kamayoq.core.reader

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
}
