package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.RemoteDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What the app's client reads of the facts in KavitaDetailsFactsTest.
class KavitaClientDetailsTest {
    private val client = KavitaClient()
    private val address = KavitaServer.url
    private val key get() = KavitaServer.authKey

    private fun described(name: String) = client.works(address, key, KavitaServer.describedLibraryId).single { it.name == name }

    private fun book(name: String) = client.works(address, key, KavitaServer.bookLibraryId).single { it.name == name }

    @Test
    fun `dado uma série com ComicInfo, quando leio os dados, então vêm resumo, autores, gêneros, etiquetas e a situação`() {
        val details = client.details(address, key, described("Info Work").id)

        assertEquals(
            RemoteDetails(
                summary = "A summary of Info Work.",
                writers = listOf("Ann Writer", "Bo Second"),
                genres = listOf("Action", "Fantasy"),
                tags = listOf("Magic", "School"),
                status = 0,
                statusLocked = false,
                totalCount = 0,
                maxCount = -100000,
            ),
            details.copy(writers = details.writers.sorted(), genres = details.genres.sorted(), tags = details.tags.sorted()),
        )
    }

    @Test
    fun `dado uma série com capítulos faltando, quando leio os dados, então vêm a situação 4 e as duas contagens`() {
        val details = client.details(address, key, described("Short Work").id)

        assertEquals(Triple(4, 5, 1), Triple(details.status, details.totalCount, details.maxCount))
    }

    @Test
    fun `dado uma série com capa, quando peço a capa, então vem a imagem`() {
        val cover = client.cover(address, key, described("Info Work").id)

        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), cover?.take(4)?.map { it.toInt() and 0xFF })
    }

    @Test
    fun `dado um EPUB sem imagem de capa, quando peço a capa, então não vem nada`() {
        assertNull(client.cover(address, key, book("Lone Book").id))
    }

    @Test
    fun `dado a lista de obras, então cada uma traz o nome do arquivo da capa, vazio no EPUB sem capa`() {
        assertTrue(described("Info Work").coverImage.endsWith(".png"))
        assertEquals("", book("Lone Book").coverImage)
    }
}
