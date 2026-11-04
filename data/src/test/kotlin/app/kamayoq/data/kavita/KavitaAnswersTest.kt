package app.kamayoq.data.kavita

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.data.LibraryStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a server that is not Kavita may answer, which the real Kavita of kavitaTest never does. This
 * is not an imitation of Kavita: every answer here is a wrong one, or an empty one.
 */
class KavitaAnswersTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val address = "http://127.0.0.1:${server.address.port}"
    private val client = KavitaClient()

    private fun answers(body: String, pagination: String? = null) {
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            pagination?.let { exchange.responseHeaders.add("Pagination", it) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `dado um objeto onde se espera a lista de bibliotecas, então a listagem falha do jeito previsto`() {
        answers("{}")

        assertFailsWith<KavitaListingFailed> { client.libraries(address, KEY) }
    }

    @Test
    fun `dado uma biblioteca sem nome, então a listagem falha do jeito previsto`() {
        answers("""[{"id": 1, "type": 0}]""")

        assertFailsWith<KavitaListingFailed> { client.libraries(address, KEY) }
    }

    @Test
    fun `dado um id que não é número, então a listagem falha do jeito previsto`() {
        answers("""[{"id": "one", "name": "Comics", "type": 0}]""")

        assertFailsWith<KavitaListingFailed> { client.libraries(address, KEY) }
    }

    @Test
    fun `dado um nome nulo, então a listagem falha em vez de criar uma biblioteca chamada null`() {
        answers("""[{"id": 1, "name": null, "type": 0}]""")

        assertFailsWith<KavitaListingFailed> { client.libraries(address, KEY) }
    }

    @Test
    fun `dado um título de capítulo que não é texto, então a listagem falha do jeito previsto`() {
        answers("""[{"minNumber": 1, "chapters": [$CHAPTER_START, "titleName": 7, "files": []}]}]""")

        assertFailsWith<KavitaListingFailed> { client.chapters(address, KEY, workId = 1) }
    }

    @Test
    fun `dado um cabeçalho de páginas sem o total, então a listagem de obras falha do jeito previsto`() {
        answers("[]", pagination = "{}")

        assertFailsWith<KavitaListingFailed> { client.works(address, KEY, libraryId = 1) }
    }

    @Test
    fun `dado um volume sem capítulos, então a listagem falha do jeito previsto`() {
        answers("""[{"id": 1}]""")

        assertFailsWith<KavitaListingFailed> { client.chapters(address, KEY, workId = 1) }
    }

    @Test
    fun `dado uma lista vazia, então a resposta é vazia e não uma falha`() {
        answers("[]")

        assertEquals(emptyList(), client.libraries(address, KEY))
        assertEquals(emptyList(), client.chapters(address, KEY, workId = 1))
    }

    @Test
    fun `dado dois capítulos com o mesmo número, então os dois continuam na lista`() {
        answers(
            """[{"minNumber": 1, "chapters": [
                $CHAPTER_START, "titleName": "", "files": []},
                $CHAPTER_START, "titleName": "", "files": []}
            ]}]""",
        )

        assertEquals(2, client.chapters(address, KEY, workId = 1).size)
    }

    @Test
    fun `dado uma falha na lista de capítulos da segunda obra, quando importo, então nada muda na Biblioteca`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        val store = LibraryStore(driver)
        store.linkKavita(address, "0.9.1.4", accountId = 1)
        val id = checkNotNull(store.kavitaServer()).id
        store.markLibraries(id, listOf(2))
        server.createContext("/api/Library/libraries") { it.answer("""[{"id": 2, "name": "Mangás", "type": 0}]""") }
        server.createContext("/api/Series/v2") {
            it.responseHeaders.add("Pagination", """{"totalPages": 1}""")
            it.answer("""[{"id": 1, "name": "Sakamoto Days", "libraryId": 2}, {"id": 2, "name": "Gantz", "libraryId": 2}]""")
        }
        server.createContext("/api/Series/volumes") {
            if ("seriesId=1" in it.requestURI.query) it.answer("[]") else it.answer("{}")
        }
        server.start()

        assertFailsWith<KavitaListingFailed> { KavitaImport(client, store).run(KEY) }

        assertEquals(emptyList(), store.works())
        driver.close()
    }

    @Test
    fun `dado uma biblioteca de tipo que o app não conhece, quando importo, então a falha é a da listagem`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        val store = LibraryStore(driver)
        store.linkKavita(address, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, listOf(2))
        server.createContext("/api/Library/libraries") { it.answer("""[{"id": 2, "name": "Nova", "type": 99}]""") }
        server.createContext("/api/Series/v2") {
            it.responseHeaders.add("Pagination", """{"totalPages": 1}""")
            it.answer("""[{"id": 1, "name": "Obra", "libraryId": 2}]""")
        }
        server.createContext("/api/Series/volumes") { it.answer("[]") }
        server.start()

        assertFailsWith<KavitaListingFailed> { KavitaImport(client, store).run(KEY) }
        driver.close()
    }

    private fun HttpExchange.answer(body: String) {
        requestBody.readBytes()
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val KEY = "test-key"
        const val CHAPTER_START =
            """{"id": 1, "minNumber": 1, "maxNumber": 1, "pages": 20, "pagesRead": 0, "volumeId": 3, "isSpecial": false"""
    }
}
