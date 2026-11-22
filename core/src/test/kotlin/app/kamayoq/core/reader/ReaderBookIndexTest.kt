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

    // Milestone C2: a place from Kavita opens by section and marker, and the page compares the count.
    @Test
    fun `dado um lugar do Kavita, então seção, marcador e seções vão no índice, sem CFI`() {
        assertEquals(
            """{"entries":[],"position":null,"flow":"paginated","section":2,"marker":"//body/p[3]","sections":5}""",
            ReaderBookIndex.json(emptyMap(), position = null, ReaderFlow.PAGES, section = 2, marker = "//body/p[3]", sections = 5),
        )
    }

    // Piece 21: a CFI that no longer resolves opens at the start of its section, not of the book.
    @Test
    fun `dado uma posição salva com a seção, então a seção vai no índice com ela`() {
        assertEquals(
            """{"entries":[],"position":"epubcfi(/6/4!/4/2)","flow":"paginated","section":2}""",
            ReaderBookIndex.json(emptyMap(), position = "epubcfi(/6/4!/4/2)", ReaderFlow.PAGES, section = 2),
        )
    }

    // Milestone B7: a split file opens at a chapter, and the page learns the chapters to say where it is.
    @Test
    fun `dado um arquivo dividido aberto num capítulo nunca lido, então o destino dele e os capítulos vão no índice`() {
        assertEquals(
            """{"entries":[],"position":null,"flow":"scrolled","start":"OEBPS/v1.xhtml#cap-2",""" +
                """"chapters":{"keys":["OEBPS/v1.xhtml#cap-1","OEBPS/v1.xhtml#cap-2"],"ofItem":[0,0,1]}}""",
            ReaderBookIndex.json(
                emptyMap(), position = null, ReaderFlow.SCROLL,
                start = "OEBPS/v1.xhtml#cap-2",
                chapters = ReaderChapters(listOf("OEBPS/v1.xhtml#cap-1", "OEBPS/v1.xhtml#cap-2"), ofItem = listOf(0, 0, 1)),
            ),
        )
    }

    @Test
    fun `dado um nome com aspas, então o JSON continua válido`() {
        assertEquals(
            """{"entries":[{"name":"a\"b.css","size":1}],"position":null,"flow":"paginated"}""",
            ReaderBookIndex.json(mapOf("a\"b.css" to 1L), position = null, ReaderFlow.PAGES),
        )
    }

    @Test
    fun `dado a aparência da página, então ela vai no índice para valer antes de o livro abrir`() {
        val look = ShownLook(TextLook.DEFAULT, PageThemes.colors(PageTheme.LIGHT, systemDark = false))
        assertEquals(
            """{"entries":[],"position":null,"flow":"paginated","look":${PageLook.json(look)}}""",
            ReaderBookIndex.json(emptyMap(), position = null, ReaderFlow.PAGES, look = look),
        )
    }
}
