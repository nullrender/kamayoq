package app.kamayoq.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.TextEdit
import app.kamayoq.core.reader.TextLookChoices
import app.kamayoq.core.reader.TextNumber
import app.kamayoq.core.reader.TextScope
import app.kamayoq.core.reader.TextSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 5: the settings panel (board SinteseLeitorPaginado; DESIGN, "Painel de formatação").
@RunWith(RobolectricTestRunner::class)
class TextSettingsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private var settings by mutableStateOf(TextSettings.DEFAULT)
    private var flow by mutableStateOf(ReaderFlow.PAGES)

    private fun show(type: WorkType = WorkType.BOOKS, touchExploration: Boolean = false, hyphenates: Boolean = true) {
        compose.setContent {
            KamayoqTheme {
                TextSettingsContent(settings, { settings = it }, type, flow, { flow = it }, touchExploration, hyphenates, systemDark = true)
            }
        }
    }

    @Test
    fun `dado o painel aberto, então ele começa em Todas as obras de texto e o mais aumenta a letra de todas`() {
        show()
        compose.onNodeWithText(text(R.string.reader_scope_all), substring = true).assertIsSelected()
        compose.onNodeWithText(text(R.string.reader_note_all)).assertExists()

        compose.onNodeWithContentDescription(text(R.string.reader_size_up))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()

        assertEquals(19, settings.general.size)
        assertNull(settings.own)
    }

    @Test
    fun `dado Esta obra escolhida, quando diminuo a margem, então só a obra muda e a nota lista o que ela tem de próprio`() {
        show()
        compose.onNodeWithText(text(R.string.reader_scope_work)).performClick()
        compose.onNodeWithText(text(R.string.reader_note_work)).assertExists()

        compose.onNodeWithContentDescription(text(R.string.reader_margins_down)).performScrollTo().performClick()

        assertEquals(20, settings.own?.margin)
        assertNull(settings.general.margin)
        compose.onNodeWithText(text(R.string.reader_note_work_own, text(R.string.reader_property_margins))).assertExists()
    }

    @Test
    fun `dado Todas com uma obra de ajuste próprio, então a nota diz que ela continua com os dela`() {
        settings = TextSettings(TextLookChoices.NONE, TextLookChoices(size = 22))
        show()
        compose.onNodeWithText(text(R.string.reader_note_all_own, text(R.string.reader_property_size))).assertExists()
    }

    @Test
    fun `dado Restaurar padrões em Todas, então os gerais voltam, o aviso diz o que voltou e Desfazer traz os de antes`() {
        settings = TextSettings.DEFAULT.change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.SIZE, 22))
        show()

        compose.onNodeWithContentDescription(text(R.string.reader_restore_all_description)).performClick()

        assertEquals(TextLookChoices.NONE, settings.general)
        compose.onNodeWithText(text(R.string.reader_restored_all)).assertExists()
        compose.onNodeWithText(text(R.string.undo)).performClick()
        assertEquals(22, settings.general.size)
    }

    @Test
    fun `dado Esta obra sem ajuste próprio, quando toco em Usar os gerais, então o aviso diz que ela já usa os gerais, sem Desfazer`() {
        show()
        compose.onNodeWithText(text(R.string.reader_scope_work)).performClick()
        compose.onNodeWithContentDescription(text(R.string.reader_restore_work_description)).assertExists()

        compose.onNodeWithContentDescription(text(R.string.reader_restore_work_description)).performClick()

        compose.onNodeWithText(text(R.string.reader_already_general)).assertExists()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.undo))).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado Justificar e hifenizar num aparelho que não hifeniza português, então o painel avisa`() {
        show(hyphenates = false)
        compose.onNodeWithText(text(R.string.reader_justify), substring = true).performScrollTo().performClick()

        assertEquals(true, settings.general.justify)
        compose.onNodeWithText(text(R.string.reader_no_hyphenation)).assertExists()
    }

    @Test
    fun `dado Justificar e hifenizar num aparelho que hifeniza, então não há aviso`() {
        show(hyphenates = true)
        compose.onNodeWithText(text(R.string.reader_justify), substring = true).performScrollTo().performClick()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.reader_no_hyphenation))).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado a aba Aparência, quando escolho Rolar, o tema Sépia e o rodapé sem nada, então cada um vale`() {
        show()
        compose.onNodeWithText(text(R.string.reader_tab_look)).performClick()
        compose.onNodeWithText(text(R.string.reader_mode_default_books)).assertExists()

        compose.onNodeWithText(text(R.string.reader_mode_scroll)).performClick()
        compose.onNodeWithContentDescription(text(R.string.reader_theme_sepia)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.reader_footer_nothing)).performScrollTo().performClick()

        assertEquals(ReaderFlow.SCROLL, flow)
        assertEquals(PageTheme.SEPIA, settings.general.theme)
        assertEquals(FooterContent.NOTHING, settings.general.footer)
    }

    @Test
    fun `dado o TalkBack ligado, então o modo não muda e o painel diz por quê`() {
        show(touchExploration = true)
        compose.onNodeWithText(text(R.string.reader_tab_look)).performClick()
        compose.onNodeWithText(text(R.string.reader_mode_pages), substring = true).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.reader_mode_talkback)).assertExists()
    }

    @Test
    fun `dado uma novel, então a nota do modo fala do padrão das novels`() {
        show(type = WorkType.NOVELS)
        compose.onNodeWithText(text(R.string.reader_tab_look)).performClick()
        compose.onNodeWithText(text(R.string.reader_mode_default_novels)).assertExists()
    }

    @Test
    fun `dado o recuo desligado e os parágrafos no piso de 0,4 em, então o menos de Parágrafos fica desligado`() {
        settings = TextSettings.DEFAULT
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Indent(false))
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.PARAGRAPH_SPACING, 4))
        show()
        compose.onNodeWithContentDescription(text(R.string.reader_paragraphs_down)).performScrollTo().assertIsNotEnabled()
    }

    // Gemini's review of slice 5, finding 11: a button with its own description also read the texts
    // inside it ("EB Garamond, Garamond").
    @Test
    fun `dado os botões com descrição própria, então o leitor de tela ouve só a descrição`() {
        show()
        for (description in listOf("EB Garamond", text(R.string.reader_restore_all_description))) {
            val texts = compose.onNodeWithContentDescription(description).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            assertNull("$description also reads $texts", texts)
        }
        compose.onNodeWithText(text(R.string.reader_tab_look)).performClick()
        val sepia = compose.onNodeWithContentDescription(text(R.string.reader_theme_sepia)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        assertNull("Sepia also reads $sepia", sepia)
    }

    // Slice 5, choice after research (restore-notice.md): the three restore notices all say the mode
    // stayed, as it does.
    @Test
    fun `dado Restaurar padrões em Todas numa obra com ajuste próprio, então o aviso diz que ela mantém os próprios e que o modo não mudou`() {
        settings = TextSettings(TextLookChoices(size = 20), TextLookChoices(margin = 32))
        show()
        compose.onNodeWithContentDescription(text(R.string.reader_restore_all_description)).performClick()
        val notice = text(R.string.reader_restored_all_keeps, text(R.string.reader_property_margins))
        compose.onNodeWithText(notice).assertExists()
        assertTrue(notice, notice.endsWith("The mode did not change."))
    }
}
