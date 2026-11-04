package app.kamayoq.core.kavita

import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaRelinkTest {
    private fun gone(id: String, number: Double?, file: String, pages: Int = 20) = KavitaRelink.Gone(id, number, pages, file)

    private fun arrived(path: String, number: Double?, file: String, pages: Int = 20) = KavitaRelink.Arrived(path, number, pages, file)

    @Test
    fun `dado um sumido e um novo com número e páginas iguais, então religa mesmo com outro nome`() {
        val links = KavitaRelink.chapters(listOf(gone("a", 2.0, "Cap 2.cbz")), listOf(arrived("90", 2.0, "Capitulo 2.cbz")))

        assertEquals(mapOf("90" to "a"), links)
    }

    @Test
    fun `dado dois sumidos com número igual, então o nome desempata`() {
        val links = KavitaRelink.chapters(
            listOf(gone("a", 1.0, "Vol. 1 Cap 1.cbz"), gone("b", 1.0, "Vol. 2 Cap 1.cbz")),
            listOf(arrived("90", 1.0, "Vol. 2 Cap 1.cbz"), arrived("91", 1.0, "Vol. 1 Cap 1.cbz")),
        )

        assertEquals(mapOf("90" to "b", "91" to "a"), links)
    }

    @Test
    fun `dado dois candidatos que nem o nome separa, então nada religa`() {
        val links = KavitaRelink.chapters(
            listOf(gone("a", 1.0, "x.cbz"), gone("b", 1.0, "x.cbz")),
            listOf(arrived("90", 1.0, "x.cbz")),
        )

        assertEquals(emptyMap(), links)
    }

    @Test
    fun `dado um sumido e um novo com páginas diferentes, então nada religa`() {
        val links = KavitaRelink.chapters(listOf(gone("a", 2.0, "x.cbz", pages = 20)), listOf(arrived("90", 2.0, "x.cbz", pages = 21)))

        assertEquals(emptyMap(), links)
    }

    @Test
    fun `dado um EPUB sem número que volta com o mesmo nome, então religa`() {
        val links = KavitaRelink.chapters(listOf(gone("a", null, "Livro.epub")), listOf(arrived("90", null, "Livro.epub")))

        assertEquals(mapOf("90" to "a"), links)
    }

    @Test
    fun `dado um EPUB sem número renomeado, então fica guardado e nada religa`() {
        val links = KavitaRelink.chapters(listOf(gone("a", null, "Livro.epub")), listOf(arrived("90", null, "Outro livro.epub")))

        assertEquals(emptyMap(), links)
    }

    @Test
    fun `dado uma série que volta com o mesmo título na mesma biblioteca, então religa`() {
        val links = KavitaRelink.works(
            listOf(KavitaRelink.GoneWork("v1", 2, "Sakamoto Days")),
            listOf(KavitaRelink.ArrivedWork("90", 2, "Sakamoto Days")),
        )

        assertEquals(mapOf("90" to "v1"), links)
    }

    @Test
    fun `dado o mesmo título em outra biblioteca, então nada religa`() {
        val links = KavitaRelink.works(
            listOf(KavitaRelink.GoneWork("v1", 2, "Sakamoto Days")),
            listOf(KavitaRelink.ArrivedWork("90", 3, "Sakamoto Days")),
        )

        assertEquals(emptyMap(), links)
    }

    @Test
    fun `dado dois títulos iguais entre os sumidos, então nada religa`() {
        val links = KavitaRelink.works(
            listOf(KavitaRelink.GoneWork("v1", 2, "Obra"), KavitaRelink.GoneWork("v2", 2, "Obra")),
            listOf(KavitaRelink.ArrivedWork("90", 2, "Obra")),
        )

        assertEquals(emptyMap(), links)
    }
}
