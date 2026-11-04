package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderBookIndexTest {

    @Test
    fun `dado as entradas de um livro novo, então o índice lista nome e tamanho, sem posição`() {
        assertEquals(
            """{"entries":[{"name":"OEBPS/a.xhtml","size":12},{"name":"b.css","size":0}],"position":null,"flow":"paginated"}""",
            ReaderBookIndex.json(mapOf("OEBPS/a.xhtml" to 12L, "b.css" to 0L), position = null, ReaderFlow.PAGES),
        )
    }

    @Test
    fun `dado uma posição salva, então ela vai no índice`() {
        assertEquals(
            """{"entries":[],"position":"epubcfi(/6/4!/4/2)","flow":"paginated"}""",
            ReaderBookIndex.json(emptyMap(), position = "epubcfi(/6/4!/4/2)", ReaderFlow.PAGES),
        )
    }

    // Piece 14: the fork places the page right only when the flow is set before the restore.
    @Test
    fun `dado o modo Rolar, então o índice leva o modo para a página aplicar antes de restaurar`() {
        assertEquals(
            """{"entries":[],"position":"epubcfi(/6/4!/4/2)","flow":"scrolled"}""",
            ReaderBookIndex.json(emptyMap(), position = "epubcfi(/6/4!/4/2)", ReaderFlow.SCROLL),
        )
    }

    @Test
    fun `dado um nome com aspas, então o JSON continua válido`() {
        assertEquals(
            """{"entries":[{"name":"a\"b.css","size":1}],"position":null,"flow":"paginated"}""",
            ReaderBookIndex.json(mapOf("a\"b.css" to 1L), position = null, ReaderFlow.PAGES),
        )
    }
}
