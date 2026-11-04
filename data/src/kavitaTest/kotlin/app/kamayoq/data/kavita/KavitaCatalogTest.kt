package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val KIND_ARCHIVE = 1
private const val KIND_EPUB = 3

class KavitaCatalogTest {
    private val client = KavitaClient()
    private val address = KavitaServer.url
    private val key get() = KavitaServer.authKey

    @Test
    fun `dado a biblioteca de teste, quando listo as bibliotecas, então ela vem com o nome dado no preparo`() {
        val library = client.libraries(address, key).single { it.id == KavitaServer.libraryId }

        assertEquals("Test library", library.name)
        assertEquals(0, library.type)
    }

    @Test
    fun `dado páginas de tamanho 1, quando listo as obras, então vêm as duas, sem repetir`() {
        val works = client.works(address, key, KavitaServer.libraryId, pageSize = 1)

        assertEquals(listOf("First Work", "Second Work"), works.map { it.name }.sorted())
        assertTrue(works.all { it.libraryId == KavitaServer.libraryId })
    }

    @Test
    fun `dado a obra com dois capítulos, quando listo os capítulos, então vêm na ordem do Kavita com o total de páginas`() {
        val work = client.works(address, key, KavitaServer.libraryId).single { it.name == "Second Work" }

        val chapters = client.chapters(address, key, work.id)

        assertEquals(listOf(1.0 to 3, 2.0 to 3), chapters.map { it.minNumber to it.pages })
    }

    @Test
    fun `dado uma obra em intervalo, quando listo os capítulos, então vêm o início, o fim, o volume e o arquivo`() {
        val work = client.works(address, key, KavitaServer.mutableLibraryId).single { it.name == "Ranged Work" }

        val chapter = client.chapters(address, key, work.id).single()

        assertEquals(1.0 to 3.0, chapter.minNumber to chapter.maxNumber)
        assertEquals(KavitaServer.chapters(work.id).single().volumeId, chapter.volumeId)
        assertEquals(-100000.0, chapter.volumeNumber)
        assertEquals("/mutable/Ranged Work/Ranged Work Ch. 001-003.cbz", chapter.files.single().path)
        assertEquals(KIND_ARCHIVE, chapter.files.single().kind)
        assertEquals(false, chapter.isSpecial)
    }

    @Test
    fun `dado um EPUB, quando listo os capítulos, então vem como especial, com o título do livro`() {
        val work = client.works(address, key, KavitaServer.bookLibraryId).single { it.name == "Lone Book" }

        val chapter = client.chapters(address, key, work.id).single()

        assertEquals(true, chapter.isSpecial)
        assertEquals("Lone Book", chapter.titleName)
        assertEquals(KIND_EPUB, chapter.files.single().kind)
    }

    @Test
    fun `dado uma chave errada, quando listo as bibliotecas, então a falha diz o código do Kavita`() {
        val failure = assertFailsWith<KavitaListingFailed> { client.libraries(address, "invented-key-1234") }

        assertEquals(401, failure.code)
    }
}
