package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class PageLookTest {

    private val sepia = PageThemes.colors(PageTheme.SEPIA, systemDark = true)

    @Test
    fun `dado os padrões, então a página recebe os valores em décimos, as cores do tema e nada escolhido`() {
        assertEquals(
            """{"font":"literata","size":18,"lineHeight":15,"paragraphSpacing":0,"margin":24,"indent":true,"justify":false,""" +
                """"background":"#11100E","text":"#E9E8E5","chosen":[]}""",
            PageLook.json(ShownLook(TextLook.DEFAULT, PageThemes.colors(PageTheme.DARK, systemDark = true))).toString(),
        )
    }

    @Test
    fun `dado letra e tamanho escolhidos no tema sépia, então a página sabe quais vencem o livro`() {
        val look = TextSettings.DEFAULT
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Font(ReadingFont.GARAMOND))
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.SIZE, 22))
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Theme(PageTheme.SEPIA))
            .effective()
        assertEquals(
            """{"font":"garamond","size":22,"lineHeight":15,"paragraphSpacing":0,"margin":24,"indent":true,"justify":false,""" +
                """"background":"#EFE4CC","text":"#3A2E20","chosen":["font","size","theme"]}""",
            PageLook.json(ShownLook(look, sepia)).toString(),
        )
    }

    @Test
    fun `dado o comando de aparência, então ele leva o tipo e a aparência inteira`() {
        assertEquals(
            """{"type":"look","look":${PageLook.json(ShownLook(TextLook.DEFAULT, sepia))}}""",
            PageLook.command(ShownLook(TextLook.DEFAULT, sepia)),
        )
    }
}
