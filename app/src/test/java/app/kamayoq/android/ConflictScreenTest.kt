package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConflictScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val chosen = mutableListOf<Boolean>()

    private fun show() = compose.setContent {
        ConflictScreen(
            localPage = 7,
            remotePage = 10,
            total = 12,
            onKeepHere = { chosen += true },
            onUseKavita = { chosen += false },
            onBack = {},
        )
    }

    private fun here() = app.getString(R.string.conflict_here, app.getString(R.string.position_page, 8, 12))

    private fun kavita() = app.getString(R.string.conflict_kavita, app.getString(R.string.position_page, 11, 12))

    @Test
    fun `dado um conflito, quando a tela abre, então vejo as duas posições sem nenhuma marcada e nada foi escolhido`() {
        show()

        // Two plain buttons: neither can come chosen.
        compose.onNodeWithText(here()).assertHasClickAction()
        compose.onNodeWithText(kavita()).assertHasClickAction()
        assertEquals(emptyList<Boolean>(), chosen)
    }

    @Test
    fun `dado um conflito, quando escolho o Kavita, então a escolha é essa`() {
        show()

        compose.onNodeWithText(kavita()).performClick()

        assertEquals(listOf(false), chosen)
    }

    @Test
    fun `dado um conflito, quando escolho este aparelho, então a escolha é essa`() {
        show()

        compose.onNodeWithText(here()).performClick()

        assertEquals(listOf(true), chosen)
    }

    // Milestone C2 of step 4: in a book a page is a section, and the total is the book read.
    @Test
    fun `dado um conflito num livro, quando a tela abre, então os dois lugares aparecem como seções`() {
        compose.setContent {
            ConflictScreen(localPage = 1, remotePage = 3, total = 3, onKeepHere = {}, onUseKavita = {}, onBack = {}, sections = true)
        }

        compose.onNodeWithText(app.getString(R.string.conflict_here, app.getString(R.string.position_section, 2, 3))).assertHasClickAction()
        compose.onNodeWithText(app.getString(R.string.conflict_kavita, app.getString(R.string.position_book_end))).assertHasClickAction()
    }

    // From the Codex review of milestone C (18/11): a book's other place is not kept aside, so the
    // screen must not promise that it is.
    @Test
    fun `dado um conflito num livro, quando a tela abre, então ela não promete guardar a outra posição`() {
        compose.setContent {
            ConflictScreen(localPage = 1, remotePage = 2, total = 3, onKeepHere = {}, onUseKavita = {}, onBack = {}, sections = true)
        }

        compose.onNodeWithText(app.getString(R.string.conflict_hint_book)).assertExists()
        compose.onNodeWithText(app.getString(R.string.conflict_hint)).assertDoesNotExist()
    }
}
