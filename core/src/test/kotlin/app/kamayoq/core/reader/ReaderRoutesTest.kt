package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderRoutesTest {

    private val origin = "https://appassets.androidplatform.net"
    private val routes = ReaderRoutes(
        readerFiles = setOf("index.html", "foliate-js/view.js"),
        book = OpenBook(token = "abc123", entries = setOf("OEBPS/a.xhtml", "OEBPS/capa nova.jpg", "100%.css")),
    )

    @Test
    fun `dado um arquivo conhecido do leitor, então ele é servido`() {
        assertEquals(ReaderRoute.ReaderFile("foliate-js/view.js"), routes.decide("$origin/reader/foliate-js/view.js"))
    }

    @Test
    fun `dado o índice do livro aberto, então ele é servido`() {
        assertEquals(ReaderRoute.BookIndex, routes.decide("$origin/book/abc123/index.json"))
    }

    @Test
    fun `dado uma entrada do livro aberto, então ela é servida pelo nome exato`() {
        assertEquals(ReaderRoute.BookEntry("OEBPS/a.xhtml"), routes.decide("$origin/book/abc123/e/OEBPS/a.xhtml"))
    }

    @Test
    fun `dado uma entrada com espaço ou por cento codificados, então o nome é decodificado uma vez só`() {
        assertEquals(ReaderRoute.BookEntry("OEBPS/capa nova.jpg"), routes.decide("$origin/book/abc123/e/OEBPS/capa%20nova.jpg"))
        assertEquals(ReaderRoute.BookEntry("100%.css"), routes.decide("$origin/book/abc123/e/100%25.css"))
    }

    @Test
    fun `dado qualquer URL fora do leitor e do livro aberto, então a resposta é 404`() {
        val outside = listOf(
            "$origin/",
            "$origin/reader/",
            "$origin/reader/outro.js",
            "$origin/reader/../book/abc123/index.json",
            "$origin/book/abc123/",
            "$origin/book/abc123/e/",
            "$origin/book/abc123/e/OEBPS/nao-existe.xhtml",
            "$origin/book/outro/index.json",
            "$origin/book/outro/e/OEBPS/a.xhtml",
            "$origin/segredo",
            "http://appassets.androidplatform.net/reader/index.html",
            "https://exemplo.invalid/reader/index.html",
            "https://appassets.androidplatform.net@exemplo.invalid/reader/index.html",
            "https://appassets.androidplatform.net.exemplo.invalid/reader/index.html",
            "file:///data/data/app.kamayoq.android/databases/kamayoq.db",
            "content://app.kamayoq.android/x",
            "",
        )
        for (url in outside) assertEquals(ReaderRoute.NotFound, routes.decide(url), url)
    }

    @Test
    fun `dado um nome com barra ou pontos codificados, então não há entrada`() {
        val tricks = listOf(
            "$origin/book/abc123/e/OEBPS%2Fa.xhtml",
            "$origin/book/abc123/e/OEBPS/%2e%2e/OEBPS/a.xhtml",
            "$origin/book/abc123/e/OEBPS/%2E%2E/OEBPS/a.xhtml",
            "$origin/book/abc123/e/OEBPS/./a.xhtml",
            "$origin/book/abc123/e/OEBPS/../OEBPS/a.xhtml",
            "$origin/book/abc123/e/OEBPS%5Ca.xhtml",
            "$origin/book/abc123/e/OEBPS//a.xhtml",
            "$origin/book/abc123/e/%2525.css",
            "$origin/book/abc123/e/100%.css",
            "$origin/book/abc123/e/%00OEBPS/a.xhtml",
        )
        for (url in tricks) assertEquals(ReaderRoute.NotFound, routes.decide(url), url)
    }

    @Test
    fun `dado uma URL com consulta ou fragmento, então a resposta é 404`() {
        assertEquals(ReaderRoute.NotFound, routes.decide("$origin/reader/index.html?x=1"))
        assertEquals(ReaderRoute.NotFound, routes.decide("$origin/book/abc123/e/OEBPS/a.xhtml#fim"))
    }

    @Test
    fun `dado nenhum livro aberto, então só o leitor responde`() {
        val closed = ReaderRoutes(readerFiles = setOf("index.html"), book = null)

        assertEquals(ReaderRoute.ReaderFile("index.html"), closed.decide("$origin/reader/index.html"))
        assertEquals(ReaderRoute.NotFound, closed.decide("$origin/book/abc123/index.json"))
    }
}
