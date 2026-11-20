package app.kamayoq.core.library

import app.kamayoq.core.progress.ReadingState
import kotlin.test.Test
import kotlin.test.assertEquals

class StateFilterTest {

    private data class Item(val title: String, val state: ReadingState?)

    private val items = listOf(
        Item("Lorde dos Mistérios", ReadingState.READING),
        Item("Sakamoto Days", null),
        Item("Escravo das Sombras", ReadingState.READING),
        Item("Dom Casmurro", ReadingState.COMPLETED),
        Item("Gantz", null),
    )

    @Test
    fun `dado cinco obras, então cada chip conta as suas, e os sem obra mostram zero`() {
        val counts = StateFilter.counts(items.map { it.state })

        assertEquals(StateFilter.entries.toSet(), counts.keys)
        assertEquals(5, counts[StateFilter.ALL])
        assertEquals(2, counts[StateFilter.READING])
        assertEquals(1, counts[StateFilter.COMPLETED])
        assertEquals(2, counts[StateFilter.NONE])
        assertEquals(0, counts[StateFilter.WANT_TO_READ])
        assertEquals(0, counts[StateFilter.ON_HOLD])
        assertEquals(0, counts[StateFilter.DROPPED])
    }

    @Test
    fun `dado o chip Lendo, então ficam só as obras em Lendo, na mesma ordem`() {
        val kept = StateFilter.READING.keep(items) { it.state }

        assertEquals(listOf("Lorde dos Mistérios", "Escravo das Sombras"), kept.map { it.title })
    }

    @Test
    fun `dado o chip Sem estado, então ficam só as obras sem estado`() {
        assertEquals(listOf("Sakamoto Days", "Gantz"), StateFilter.NONE.keep(items) { it.state }.map { it.title })
    }

    @Test
    fun `dado o chip Todas, então ficam todas as obras`() {
        assertEquals(items, StateFilter.ALL.keep(items) { it.state })
    }

    @Test
    fun `dado um chip sem obras, então a lista fica vazia`() {
        assertEquals(emptyList(), StateFilter.ON_HOLD.keep(items) { it.state })
    }
}
