package app.kamayoq.core.kavita

import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

// The numbers are the ones KavitaDetailsFactsTest measured in Kavita 0.9.1.4.
class KavitaDetailsTest {
    private fun remote(
        status: Int = 0,
        locked: Boolean = false,
        total: Int = 0,
        max: Int = -100000,
        summary: String = "",
        writers: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = RemoteDetails(summary, writers, genres, tags, status, locked, total, max)

    private fun publication(remote: RemoteDetails) = KavitaTranslation.publication(remote)

    @Test
    fun `dado a situação 0 sem contagem e sem trava, então a situação e o que falta são desconhecidos`() {
        assertEquals(Publication.UNKNOWN, publication(remote()))
    }

    @Test
    fun `dado a situação 0 travada por quem edita no Kavita, então é Em andamento, e o que falta segue desconhecido`() {
        assertEquals(Publication(PublicationStatus.ONGOING, null), publication(remote(locked = true)))
    }

    @Test
    fun `dado a situação 0 com 10 previstos e o 4 no Kavita, então é Em andamento e faltam 6`() {
        assertEquals(Publication(PublicationStatus.ONGOING, 6), publication(remote(total = 10, max = 4)))
    }

    @Test
    fun `dado a situação 2 com 2 de 2, então é Concluída e não falta nada`() {
        assertEquals(Publication(PublicationStatus.COMPLETED, 0), publication(remote(status = 2, total = 2, max = 2)))
    }

    @Test
    fun `dado a situação 4, Ended, com 5 previstos e o 1 no Kavita, então é Concluída e faltam 4`() {
        assertEquals(Publication(PublicationStatus.COMPLETED, 4), publication(remote(status = 4, total = 5, max = 1)))
    }

    @Test
    fun `dado a situação 2 editada no Kavita sem contagem, então é Concluída, e o que falta é desconhecido`() {
        assertEquals(Publication(PublicationStatus.COMPLETED, null), publication(remote(status = 2, locked = true)))
    }

    @Test
    fun `dado as situações 1 e 3, então são Em hiato e Cancelada`() {
        assertEquals(PublicationStatus.HIATUS, publication(remote(status = 1, locked = true)).status)
        assertEquals(PublicationStatus.CANCELLED, publication(remote(status = 3, locked = true)).status)
    }

    @Test
    fun `dado um número de situação que o Kavita 0_9_1 não tem, então a situação é desconhecida`() {
        assertEquals(null, publication(remote(status = 9, locked = true)).status)
    }

    @Test
    fun `dado gêneros e etiquetas com um repetido, então as etiquetas da obra são os gêneros e depois as etiquetas, sem repetir`() {
        val details = KavitaTranslation.details(remote(genres = listOf("Fantasy", "Action"), tags = listOf("Magic", "action")))

        assertEquals(listOf("Fantasy", "Action", "Magic"), details.tags)
    }

    @Test
    fun `dado dois autores, então ficam na ordem do Kavita`() {
        assertEquals(listOf("Ann Writer", "Bo Second"), KavitaTranslation.details(remote(writers = listOf("Ann Writer", "Bo Second"))).authors)
    }

    @Test
    fun `dado uma sinopse em HTML com parágrafos e entidades, então ela chega em texto, um parágrafo por linha`() {
        val details = KavitaTranslation.details(remote(summary = "<p>Sunny &amp; a sombra.</p><p>Segunda <b>linha</b>.</p>"))

        assertEquals("Sunny & a sombra.\nSegunda linha.", details.summary)
    }

    @Test
    fun `dado a situação 2 com 1 de 1, então os dados da obra trazem a mesma situação da tradução`() {
        assertEquals(Publication(PublicationStatus.COMPLETED, 0), KavitaTranslation.details(remote(status = 2, total = 1, max = 1)).publication)
    }
}
