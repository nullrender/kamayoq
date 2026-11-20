package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySectionsTest {
    private data class Entry(val title: String, val type: WorkType)

    private fun sections(vararg works: Entry) =
        LibrarySections.of(works.toList(), Entry::type, Entry::title).map { section -> section.type to section.works.map { it.title } }

    @Test
    fun `dado obras dos três tipos misturadas, então as seções vêm na ordem Quadrinhos, Novels e Livros`() {
        val result = sections(
            Entry("Dom Casmurro", WorkType.BOOKS),
            Entry("Shadow Slave", WorkType.NOVELS),
            Entry("Sakamoto Days", WorkType.COMICS),
        )

        assertEquals(
            listOf(
                WorkType.COMICS to listOf("Sakamoto Days"),
                WorkType.NOVELS to listOf("Shadow Slave"),
                WorkType.BOOKS to listOf("Dom Casmurro"),
            ),
            result,
        )
    }

    @Test
    fun `dado nenhuma novel, então a seção Novels não aparece`() {
        val result = sections(Entry("Dom Casmurro", WorkType.BOOKS), Entry("Kaiju No. 8", WorkType.COMICS))

        assertEquals(listOf(WorkType.COMICS, WorkType.BOOKS), result.map { it.first })
    }

    @Test
    fun `dado nenhuma obra, então não há seção`() {
        assertEquals(emptyList(), sections())
    }

    @Test
    fun `dado títulos com artigo do português e do inglês, então a seção os ordena pela palavra seguinte`() {
        val result = sections(
            Entry("A Moreninha", WorkType.BOOKS),
            Entry("The Hobbit", WorkType.BOOKS),
            Entry("Os Lusíadas", WorkType.BOOKS),
            Entry("Dom Casmurro", WorkType.BOOKS),
            Entry("O Conde de Monte Cristo", WorkType.BOOKS),
            Entry("Emma", WorkType.BOOKS),
            Entry("Uma Aprendizagem", WorkType.BOOKS),
        )

        assertEquals(
            listOf("Uma Aprendizagem", "O Conde de Monte Cristo", "Dom Casmurro", "Emma", "The Hobbit", "Os Lusíadas", "A Moreninha"),
            result.single().second,
        )
    }

    @Test
    fun `dado títulos com acento, maiúsculas e números, então a ordem ignora acento e caixa e compara números pelo valor`() {
        val result = sections(
            Entry("zorro", WorkType.COMICS),
            Entry("Ícaro", WorkType.COMICS),
            Entry("Obra 10", WorkType.COMICS),
            Entry("Obra 2", WorkType.COMICS),
            Entry("história", WorkType.COMICS),
        )

        assertEquals(listOf("história", "Ícaro", "Obra 2", "Obra 10", "zorro"), result.single().second)
    }

    @Test
    fun `dado um título que é só um artigo, então ele fica como está`() {
        val result = sections(Entry("A", WorkType.NOVELS), Entry("B", WorkType.NOVELS))

        assertEquals(listOf("A", "B"), result.single().second)
    }
}
