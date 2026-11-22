package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextLookTest {

    private val all = TextScope.ALL_TEXT_WORKS
    private val thisWork = TextScope.THIS_WORK

    @Test
    fun `dado nada escolhido, então a página usa os padrões do desenho e nada vence o livro`() {
        val look = TextSettings.DEFAULT.effective()
        assertEquals(18, look.size)
        assertEquals(15, look.lineHeight)
        assertEquals(0, look.paragraphSpacing)
        assertEquals(24, look.margin)
        assertTrue(look.indent)
        assertFalse(look.justify)
        assertEquals(PageTheme.AUTO, look.theme)
        assertEquals(FooterContent.ALL, look.footer)
        assertEquals(emptySet(), look.chosen)
    }

    @Test
    fun `dado um tamanho mudado em Todas, então a página o usa e o tamanho passa a vencer o livro`() {
        val look = TextSettings.DEFAULT.change(all, TextEdit.Number(TextNumber.SIZE, 21)).effective()
        assertEquals(21, look.size)
        assertEquals(setOf(TextProperty.SIZE), look.chosen)
    }

    @Test
    fun `dado um valor fora dos limites, então ele fica no limite`() {
        val big = TextSettings.DEFAULT.change(all, TextEdit.Number(TextNumber.SIZE, 40)).effective()
        assertEquals(26, big.size)
        val small = TextSettings.DEFAULT.change(all, TextEdit.Number(TextNumber.MARGIN, 2)).effective()
        assertEquals(12, small.margin)
    }

    @Test
    fun `dado o botão de mais e o de menos, então o valor anda um passo e para no limite`() {
        val up = TextSettings.DEFAULT.change(all, TextEdit.Step(TextNumber.MARGIN, up = true)).effective()
        assertEquals(28, up.margin)
        var atTop = TextSettings.DEFAULT
        repeat(20) { atTop = atTop.change(all, TextEdit.Step(TextNumber.LINE_HEIGHT, up = true)) }
        assertEquals(20, atTop.effective().lineHeight)
        val down = TextSettings.DEFAULT.change(all, TextEdit.Step(TextNumber.PARAGRAPH_SPACING, up = false)).effective()
        assertEquals(0, down.paragraphSpacing)
    }

    @Test
    fun `dado uma obra com ajuste próprio, então ele vale sobre o geral e o resto segue o geral`() {
        val settings = TextSettings.DEFAULT
            .change(all, TextEdit.Number(TextNumber.SIZE, 20))
            .change(all, TextEdit.Theme(PageTheme.SEPIA))
            .change(thisWork, TextEdit.Font(ReadingFont.GARAMOND))
            .change(thisWork, TextEdit.Number(TextNumber.SIZE, 22))
        val look = settings.effective()
        assertEquals(22, look.size)
        assertEquals(ReadingFont.GARAMOND, look.font)
        assertEquals(PageTheme.SEPIA, look.theme)
        assertEquals(setOf(TextProperty.SIZE, TextProperty.THEME, TextProperty.FONT), look.chosen)
    }

    @Test
    fun `dado o seletor em Todas numa obra com ajuste próprio, então mudar o geral não muda o que ela tem`() {
        val settings = TextSettings.DEFAULT
            .change(thisWork, TextEdit.Number(TextNumber.SIZE, 22))
            .change(all, TextEdit.Number(TextNumber.SIZE, 16))
        assertEquals(22, settings.effective().size)
        assertEquals(16, settings.general.size)
    }

    @Test
    fun `dado o seletor em Todas, então o painel mostra os gerais e não os desta obra`() {
        val settings = TextSettings.DEFAULT
            .change(all, TextEdit.Number(TextNumber.SIZE, 20))
            .change(thisWork, TextEdit.Number(TextNumber.SIZE, 22))
        assertEquals(20, settings.shown(all).size)
        assertEquals(22, settings.shown(thisWork).size)
    }

    @Test
    fun `dado o seletor trocado sem mudar nada, então nenhum valor é copiado`() {
        val settings = TextSettings.DEFAULT.change(all, TextEdit.Number(TextNumber.SIZE, 20))
        assertNull(settings.own)
        assertEquals(20, settings.shown(thisWork).size)
    }

    @Test
    fun `dado o ajuste próprio, então ele lista as propriedades na ordem do painel`() {
        val own = TextSettings.DEFAULT
            .change(thisWork, TextEdit.Theme(PageTheme.BLACK))
            .change(thisWork, TextEdit.Font(ReadingFont.PLEX))
            .own
        assertEquals(listOf(TextProperty.FONT, TextProperty.THEME), own?.properties)
    }

    @Test
    fun `dado o recuo desligado com o espaço entre parágrafos em zero, então o espaço sobe para 0,8 em`() {
        val look = TextSettings.DEFAULT.change(all, TextEdit.Indent(false)).effective()
        assertFalse(look.indent)
        assertEquals(8, look.paragraphSpacing)
    }

    @Test
    fun `dado o recuo desligado com espaço já escolhido, então o espaço fica como estava`() {
        val look = TextSettings.DEFAULT
            .change(all, TextEdit.Number(TextNumber.PARAGRAPH_SPACING, 12))
            .change(all, TextEdit.Indent(false))
            .effective()
        assertEquals(12, look.paragraphSpacing)
    }

    @Test
    fun `dado o recuo desligado, então o espaço entre parágrafos não desce de 0,4 em`() {
        val look = TextSettings.DEFAULT
            .change(all, TextEdit.Indent(false))
            .change(all, TextEdit.Number(TextNumber.PARAGRAPH_SPACING, 0))
            .effective()
        assertEquals(4, look.paragraphSpacing)
    }

    @Test
    fun `dado o recuo desligado nesta obra e o geral com recuo, então a regra do espaço olha o que a obra mostra`() {
        val look = TextSettings.DEFAULT.change(thisWork, TextEdit.Indent(false)).effective()
        assertEquals(8, look.paragraphSpacing)
        assertEquals(0, TextSettings.DEFAULT.change(thisWork, TextEdit.Indent(false)).general.paragraphSpacing ?: 0)
    }

    @Test
    fun `dado Justificar e hifenizar ligado, então a página justifica`() {
        val look = TextSettings.DEFAULT.change(all, TextEdit.Justify(true)).effective()
        assertTrue(look.justify)
        assertEquals(setOf(TextProperty.JUSTIFY), look.chosen)
    }

    @Test
    fun `dado Restaurar padrões em Todas, então os gerais voltam ao padrão e a obra mantém os próprios`() {
        val settings = TextSettings.DEFAULT
            .change(all, TextEdit.Number(TextNumber.SIZE, 20))
            .change(all, TextEdit.Footer(FooterContent.NOTHING))
            .change(thisWork, TextEdit.Font(ReadingFont.ATKINSON))
        val (restored, outcome) = settings.restore(all)
        assertEquals(TextRestore.GENERAL_DEFAULTS, outcome)
        assertEquals(TextLookChoices.NONE, restored.general)
        assertEquals(ReadingFont.ATKINSON, restored.effective().font)
        assertEquals(18, restored.effective().size)
    }

    @Test
    fun `dado Usar os gerais nesta obra, então ela perde os próprios e segue os gerais`() {
        val settings = TextSettings.DEFAULT
            .change(all, TextEdit.Number(TextNumber.SIZE, 20))
            .change(thisWork, TextEdit.Number(TextNumber.SIZE, 24))
        val (restored, outcome) = settings.restore(thisWork)
        assertEquals(TextRestore.OWN_DISCARDED, outcome)
        assertNull(restored.own)
        assertEquals(20, restored.effective().size)
    }

    @Test
    fun `dado Usar os gerais numa obra sem ajuste próprio, então nada muda e o painel diz que ela já usa os gerais`() {
        val settings = TextSettings.DEFAULT.change(all, TextEdit.Number(TextNumber.SIZE, 20))
        val (restored, outcome) = settings.restore(thisWork)
        assertEquals(TextRestore.ALREADY_GENERAL, outcome)
        assertEquals(settings, restored)
    }

    @Test
    fun `dado um valor igual ao padrão escolhido de propósito, então ele conta como escolhido`() {
        val look = TextSettings.DEFAULT.change(all, TextEdit.Font(ReadingFont.LITERATA)).effective()
        assertEquals(setOf(TextProperty.FONT), look.chosen)
    }
}
