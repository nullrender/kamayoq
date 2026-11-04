package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaLibraryTest {
    private fun work(name: String) = KavitaServer.works().single { it.name == name }

    @Test
    fun `dado a pasta de teste com duas obras, quando a varredura termina, então a biblioteca lista duas séries`() {
        assertEquals(listOf("First Work", "Second Work"), KavitaServer.works().map { it.name }.sorted())
    }

    @Test
    fun `dado o capítulo de 12 páginas, quando o Kavita o varre, então o capítulo tem 12 páginas`() {
        assertEquals(12, KavitaServer.chapters(work("First Work").id).single().pages)
    }

    @Test
    fun `dado a obra com dois capítulos, quando peço os volumes, então vêm os dois na ordem 1, 2`() {
        assertEquals(listOf("1", "2"), KavitaServer.chapters(work("Second Work").id).map { it.number })
    }
}
