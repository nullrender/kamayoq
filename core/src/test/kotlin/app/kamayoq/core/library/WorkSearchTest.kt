package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkSearchTest {

    @Test
    fun `dado parte do título, então a obra é achada`() {
        assertTrue(WorkSearch.matches("escravo", "Escravo das Sombras", null))
        assertTrue(WorkSearch.matches("das som", "Escravo das Sombras", null))
    }

    @Test
    fun `dado o título digitado sem acento ou em maiúsculas, então a obra com acento é achada`() {
        assertTrue(WorkSearch.matches("lusiadas", "Os Lusíadas", null))
        assertTrue(WorkSearch.matches("LUSÍADAS", "Os Lusíadas", null))
    }

    @Test
    fun `dado só o título alternativo, então a obra é achada por ele`() {
        assertTrue(WorkSearch.matches("shadow", "Escravo das Sombras", "Shadow Slave"))
    }

    @Test
    fun `dado um texto que não está no título nem no alternativo, então a obra não é achada`() {
        assertFalse(WorkSearch.matches("kaiju", "Sakamoto Days", null))
        assertFalse(WorkSearch.matches("kaiju", "Escravo das Sombras", "Shadow Slave"))
    }

    @Test
    fun `dado espaços a mais na busca, então eles não contam`() {
        assertTrue(WorkSearch.matches("  escravo   das ", "Escravo das Sombras", null))
    }

    @Test
    fun `dado a busca vazia, então toda obra é achada`() {
        assertTrue(WorkSearch.matches("", "Sakamoto Days", null))
        assertTrue(WorkSearch.matches("   ", "Sakamoto Days", null))
    }
}
