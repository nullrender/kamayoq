package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderContentsTest {
    @Test
    fun `dado um sumário com volume e capítulos então os índices e níveis ficam na ordem do livro`() {
        assertEquals(
            listOf(ReaderContentsItem(0, "Volume 1", 0), ReaderContentsItem(1, "Capítulo 1", 1)),
            ReaderContents.parse("""[{"index":0,"title":"Volume 1","depth":0},{"index":1,"title":"Capítulo 1","depth":1}]"""),
        )
    }

    @Test
    fun `dado um livro sem sumário então a lista é vazia`() {
        assertEquals(emptyList(), ReaderContents.parse("[]"))
    }

    @Test
    fun `dado um sumário malformado ou fora dos limites então a lista é recusada`() {
        for (raw in listOf(
            "null", "{}", "[", "[{}]",
            """[{"index":-1,"title":"x","depth":0}]""",
            """[{"index":1,"title":"x","depth":0}]""",
            """[{"index":0,"title":"x","depth":17}]""",
            """[{"index":0,"title":"x","depth":-1}]""",
            """[{"index":"0","title":"x","depth":0}]""",
            """[{"index":0,"title":true,"depth":0}]""",
            """[{"index":0,"title":"${"a".repeat(513)}","depth":0}]""",
            " ".repeat(ReaderContents.MAX_JSON + 1),
        )) assertNull(ReaderContents.parse(raw), raw.take(100))
    }

    @Test
    fun `dado mais itens que o limite então o sumário é recusado`() {
        val raw = (0..ReaderContents.MAX_ITEMS).joinToString(",", "[", "]") {
            """{"index":$it,"title":"Capítulo","depth":0}"""
        }
        assertNull(ReaderContents.parse(raw))
    }
}
