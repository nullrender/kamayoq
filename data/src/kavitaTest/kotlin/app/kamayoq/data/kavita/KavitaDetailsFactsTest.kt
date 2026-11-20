package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What Kavita 0.9.1 gives for the Work screen: cover, summary, writers, genres, tags and the state
 * of the publication. As in [KavitaFactsTest], no honest red commit: docs/decisions/kavita-step-3-2026-10-30.md,
 * decision 4.
 */
class KavitaDetailsFactsTest {
    private fun described(name: String) = KavitaServer.works(KavitaServer.describedLibraryId).single { it.name == name }

    private fun book(name: String) = KavitaServer.works(KavitaServer.bookLibraryId).single { it.name == name }

    private fun JsonObject.names(field: String, part: String) =
        getValue(field).jsonArray.map { it.jsonObject.getValue(part).jsonPrimitive.content }

    private fun JsonObject.number(field: String) = getValue(field).jsonPrimitive.int

    /** Publication status, total count and max count, as three numbers. */
    private fun JsonObject.publication() = Triple(number("publicationStatus"), number("totalCount"), number("maxCount"))

    @Test
    fun `dado um ComicInfo com resumo, autores, gêneros e etiquetas, então o metadata da série devolve cada um`() {
        val metadata = KavitaServer.metadata(described("Info Work").id)

        assertEquals("A summary of Info Work.", metadata.getValue("summary").jsonPrimitive.content)
        assertEquals(listOf("Ann Writer", "Bo Second"), metadata.names("writers", "name").sorted())
        assertEquals(listOf("Action", "Fantasy"), metadata.names("genres", "title").sorted())
        assertEquals(listOf("Magic", "School"), metadata.names("tags", "title").sorted())
    }

    @Test
    fun `dado um ComicInfo sem Count, então a situação é 0, como em andamento, com total 0 e máximo -100000`() {
        assertEquals(Triple(0, 0, -100000), KavitaServer.metadata(described("Info Work").id).publication())
    }

    @Test
    fun `dado uma série sem ComicInfo, então a situação também é 0, sem nada que a separe de em andamento`() {
        val metadata = KavitaServer.metadata(described("Plain Work").id)

        assertEquals(Triple(0, 0, -100000), metadata.publication())
        assertEquals("", metadata.getValue("summary").jsonPrimitive.content)
        assertEquals(emptyList(), metadata.names("writers", "name"))
    }

    @Test
    fun `dado Count 2 e os dois capítulos, então a situação é 2, concluída, com total 2 e máximo 2`() {
        assertEquals(Triple(2, 2, 2), KavitaServer.metadata(described("Ended Work").id).publication())
    }

    @Test
    fun `dado Count 5 e só o capítulo 1, então a situação é 4, Ended, com total 5 e máximo 1`() {
        assertEquals(Triple(4, 5, 1), KavitaServer.metadata(described("Short Work").id).publication())
    }

    @Test
    fun `dado a situação editada no Kavita para 1 e depois 3, então o metadata devolve cada uma, travada`() {
        val work = described("Edited Work")
        val edit = { status: Int ->
            KavitaServer.editMetadata(work.id) {
                JsonObject(it + mapOf("publicationStatus" to JsonPrimitive(status), "publicationStatusLocked" to JsonPrimitive(true)))
            }
        }

        edit(1)
        val hiatus = KavitaServer.metadata(work.id)
        edit(3)
        val cancelled = KavitaServer.metadata(work.id)

        assertEquals(1 to true, hiatus.number("publicationStatus") to hiatus.getValue("publicationStatusLocked").jsonPrimitive.boolean)
        assertEquals(3 to true, cancelled.number("publicationStatus") to cancelled.getValue("publicationStatusLocked").jsonPrimitive.boolean)
    }

    @Test
    fun `dado a biblioteca com metadados desligados, então o ComicInfo não chega: sem resumo e situação 0`() {
        val work = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Unread Info Work" }
        val metadata = KavitaServer.metadata(work.id)

        assertEquals("", metadata.getValue("summary").jsonPrimitive.content)
        assertEquals(emptyList(), metadata.names("genres", "title"))
        assertEquals(Triple(0, 0, -100000), metadata.publication())
    }

    @Test
    fun `dado um EPUB com creator, description e subject, então viram autor, resumo e gênero, e a situação é 2 com 1 de 1`() {
        val metadata = KavitaServer.metadata(book("Described Book").id)

        assertEquals("A book about Bento.", metadata.getValue("summary").jsonPrimitive.content)
        assertEquals(listOf("Machado de Assis"), metadata.names("writers", "name"))
        assertEquals(listOf("romance"), metadata.names("genres", "title"))
        assertEquals(Triple(2, 1, 1), metadata.publication())
    }

    @Test
    fun `dado um EPUB sem metadado nenhum, então a situação também é 2 com 1 de 1`() {
        assertEquals(Triple(2, 1, 1), KavitaServer.metadata(book("Lone Book").id).publication())
    }

    @Test
    fun `dado a chave só no cabeçalho, quando peço a capa da série, então vem um PNG`() {
        val (answer, bytes) = KavitaServer.bytes("/api/Image/series-cover?seriesId=${described("Info Work").id}", key = KavitaServer.authKey)

        assertEquals(200, answer)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `dado nenhuma chave, quando peço a capa da série, então o Kavita recusa com 401`() {
        val (answer, _) = KavitaServer.bytes("/api/Image/series-cover?seriesId=${described("Info Work").id}")

        assertEquals(401, answer)
    }

    @Test
    fun `dado um EPUB sem imagem de capa, quando peço a capa, então o Kavita responde 404`() {
        val (answer, _) = KavitaServer.bytes("/api/Image/series-cover?seriesId=${book("Lone Book").id}", key = KavitaServer.authKey)

        assertEquals(404, answer)
    }

    @Test
    fun `dado a lista de séries, então cada uma traz o nome do arquivo da capa, e o EPUB sem capa traz vazio`() {
        assertEquals(true, KavitaServer.coverNames(KavitaServer.describedLibraryId).getValue("Info Work").endsWith(".png"))
        assertEquals("", KavitaServer.coverNames(KavitaServer.bookLibraryId).getValue("Lone Book"))
    }
}
