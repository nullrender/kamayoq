package app.kamayoq.core.reader

import app.kamayoq.core.progress.BasisPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReaderMessageTest {

    private fun relocated(
        section: String = "1",
        sections: String = "3",
        cfi: String = "\"epubcfi(/6/4!/4/2)\"",
        fraction: String = "0.5",
        reason: String = "\"page\"",
    ) = """{"type":"relocated","section":$section,"sections":$sections,"cfi":$cfi,"fraction":$fraction,"reason":$reason}"""

    @Test
    fun `dado ready, então o leitor está pronto`() {
        assertEquals(ReaderMessage.Ready, ReaderMessage.parse("""{"type":"ready"}"""))
    }

    @Test
    fun `dado relocated com fração 0,5, então são 5 000 pontos-base`() {
        val message = assertIs<ReaderMessage.Relocated>(ReaderMessage.parse(relocated()))

        assertEquals(1, message.section)
        assertEquals(3, message.sections)
        assertEquals("epubcfi(/6/4!/4/2)", message.cfi)
        assertEquals(5_000, message.position.value)
    }

    @Test
    fun `dado uma fração inválida, então a mensagem é recusada, não vira zero`() {
        for (fraction in listOf("-0.1", "1.1", "\"0.5\"", "null", "1e999")) {
            assertIs<ReaderMessage.Refused>(ReaderMessage.parse(relocated(fraction = fraction)), fraction)
        }
    }

    @Test
    fun `dado uma seção fora do livro, então a mensagem é recusada`() {
        for ((section, sections) in listOf("-1" to "3", "3" to "3", "0" to "0", "1.5" to "3", "\"1\"" to "3")) {
            assertIs<ReaderMessage.Refused>(ReaderMessage.parse(relocated(section, sections)), "$section de $sections")
        }
    }

    @Test
    fun `dado um CFI gigante ou que não é CFI, então a mensagem é recusada`() {
        val giant = "\"epubcfi(/6/${"4".repeat(ReaderMessage.MAX_CFI_LENGTH)})\""

        assertIs<ReaderMessage.Refused>(ReaderMessage.parse(relocated(cfi = giant)))
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse(relocated(cfi = "\"/6/4\"")))
    }

    @Test
    fun `dado failed com um código, então a falha chega ao Kotlin`() {
        assertEquals(ReaderMessage.Failed("open-failed"), ReaderMessage.parse("""{"type":"failed","code":"open-failed"}"""))
    }

    @Test
    fun `dado failed com um código estranho, então a mensagem é recusada`() {
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"failed","code":"<img src=x>"}"""))
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"failed"}"""))
    }

    @Test
    fun `dado uma mensagem de tipo desconhecido, então ela é recusada e diz o motivo para o registro`() {
        assertEquals(ReaderMessage.Refused("unknown type"), ReaderMessage.parse("""{"type":"PWNED"}"""))
    }

    @Test
    fun `dado JSON quebrado, texto vazio ou algo que não é objeto, então a mensagem é recusada`() {
        for (raw in listOf("", "{", "[]", "1", "ready", "\"ready\"")) {
            assertIs<ReaderMessage.Refused>(ReaderMessage.parse(raw), raw)
        }
    }

    @Test
    fun `dado uma mensagem maior que o limite, então ela é recusada sem ser lida`() {
        val huge = """{"type":"ready","x":"${"a".repeat(ReaderMessage.MAX_MESSAGE_LENGTH)}"}"""

        assertEquals(ReaderMessage.Refused("too long"), ReaderMessage.parse(huge))
    }

    @Test
    fun `dado um campo a mais, então a mensagem é recusada`() {
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"ready","extra":1}"""))
    }

    @Test
    fun `dado link com um endereço, então o endereço chega ao Kotlin como veio, sem ser aberto`() {
        assertEquals(
            ReaderMessage.Link("https://exemplo.invalid/a?b=1"),
            ReaderMessage.parse("""{"type":"link","href":"https://exemplo.invalid/a?b=1"}"""),
        )
        assertEquals(ReaderMessage.Link("javascript:alert(1)"), ReaderMessage.parse("""{"type":"link","href":"javascript:alert(1)"}"""))
    }

    @Test
    fun `dado link sem endereço, com endereço gigante ou com campo a mais, então a mensagem é recusada`() {
        val giant = "https://exemplo.invalid/" + "a".repeat(ReaderMessage.MAX_LINK_LENGTH)

        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"link"}"""))
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"link","href":"$giant"}"""))
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"link","href":"https://x.invalid/","x":1}"""))
    }

    @Test
    fun `dado a fração 1, então a posição é o fim, sem dizer que o livro acabou`() {
        val message = assertIs<ReaderMessage.Relocated>(ReaderMessage.parse(relocated(fraction = "1")))

        assertEquals(BasisPoints.END, message.position.value)
    }

    // Piece 14: the guard tells the person's moves from the fork's own re-anchoring by this reason.
    @Test
    fun `dado relocated com cada motivo do paginator, então o motivo chega e diz se foi a pessoa`() {
        val byPerson = mapOf(
            "page" to true, "snap" to true, "scroll" to true, "navigation" to true, "selection" to true,
            "anchor" to false, "container-scroll" to false,
        )
        for ((reason, person) in byPerson) {
            val message = assertIs<ReaderMessage.Relocated>(ReaderMessage.parse(relocated(reason = "\"$reason\"")), reason)
            assertEquals(person, message.reason.byPerson, reason)
        }
    }

    @Test
    fun `dado relocated sem motivo ou com motivo desconhecido, então a mensagem é recusada`() {
        for (reason in listOf("\"PWNED\"", "null", "1", "\"\"")) {
            assertIs<ReaderMessage.Refused>(ReaderMessage.parse(relocated(reason = reason)), reason)
        }
        assertIs<ReaderMessage.Refused>(
            ReaderMessage.parse("""{"type":"relocated","section":1,"sections":3,"cfi":"epubcfi(/6/4!/4/2)","fraction":0.5}"""),
        )
    }

    @Test
    fun `dado restored, então o leitor confirma a restauração, e com campo a mais é recusado`() {
        assertEquals(ReaderMessage.Restored, ReaderMessage.parse("""{"type":"restored"}"""))
        assertIs<ReaderMessage.Refused>(ReaderMessage.parse("""{"type":"restored","cfi":"epubcfi(/6/4!/4/2)"}"""))
    }
}
