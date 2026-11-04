package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.KavitaConnection
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KavitaClientTest {
    private val client = KavitaClient()

    @Test
    fun `dado o Kavita do CI e a chave certa, quando conecto, então o resultado é Conectado com a versão 0_9_1`() {
        val connection = client.connect(KavitaServer.url, KavitaServer.authKey)

        assertIs<KavitaConnection.Connected>(connection)
        assertTrue(connection.version.startsWith("0.9.1"), connection.version)
        assertEquals(1, connection.accountId)
    }

    @Test
    fun `dado uma chave errada, quando conecto, então o resultado é Recusado`() {
        assertEquals(KavitaConnection.Refused, client.connect(KavitaServer.url, "invented-key-1234"))
    }

    @Test
    fun `dado uma porta onde nada responde, quando conecto, então o resultado é Inacessível`() {
        assertEquals(KavitaConnection.Unreachable, client.connect("http://localhost:1", "invented-key-1234"))
    }

    @Test
    fun `dado um endereço que não é URL, quando conecto, então o resultado é Inacessível`() {
        assertEquals(KavitaConnection.Unreachable, client.connect("abc", "invented-key-1234"))
    }

    @Test
    fun `dado um servidor que responde 200 sem ser o Kavita, quando conecto, então o resultado é Não é Kavita`() {
        withServer(answer(200, "<html>Welcome</html>")) { address ->
            assertEquals(KavitaConnection.NotKavita, client.connect(address, "invented-key-1234"))
        }
    }

    @Test
    fun `dado um endereço que responde 404, quando conecto, então o resultado é Não é Kavita`() {
        withServer(answer(404, "")) { address ->
            assertEquals(KavitaConnection.NotKavita, client.connect(address, "invented-key-1234"))
        }
    }

    @Test
    fun `dado um servidor cuja versão não é um texto com conteúdo, quando conecto, então o resultado é Não é Kavita`() {
        for (version in listOf("null", "\"\"", "123", "true", "[]")) {
            withServer(answer(200, """{"kavitaVersion":$version}""")) { address ->
                assertEquals(KavitaConnection.NotKavita, client.connect(address, "invented-key-1234"), version)
            }
        }
    }

    @Test
    fun `dado um redirecionamento para outro servidor, quando conecto, então a chave não chega a ele`() {
        val keysSeen = mutableListOf<String?>()
        withServer({ exchange ->
            keysSeen += exchange.requestHeaders.getFirst("x-api-key")
            answer(200, """{"kavitaVersion":"0.9.1.4"}""").handle(exchange)
        }) { outsider ->
            withServer({ exchange ->
                exchange.responseHeaders.add("Location", "$outsider/api/Account")
                answer(302, "").handle(exchange)
            }) { address ->
                assertEquals(KavitaConnection.NotKavita, client.connect(address, "invented-key-1234"))
            }
        }
        assertEquals(emptyList(), keysSeen)
    }

    @Test
    fun `dado uma chave colada com quebra de linha no fim, quando conecto, então o resultado é Conectado`() {
        assertIs<KavitaConnection.Connected>(client.connect(KavitaServer.url, KavitaServer.authKey + "\n"))
    }

    @Test
    fun `dado uma chave com caractere que não cabe num cabeçalho, quando conecto, então o resultado é Recusado`() {
        assertEquals(KavitaConnection.Refused, client.connect(KavitaServer.url, "invented\nkey"))
    }

    private fun answer(code: Int, body: String) = HttpHandler { exchange ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun withServer(handler: HttpHandler, test: (address: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/", handler)
        server.start()
        try {
            test("http://localhost:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
