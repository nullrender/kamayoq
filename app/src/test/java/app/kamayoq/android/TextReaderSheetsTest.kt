package app.kamayoq.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 5: Go to and the novel's chapters in the reader (board SinteseLeitorPaginado).
@RunWith(RobolectricTestRunner::class)
class TextReaderSheetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado Ir para num livro, quando ponho a metade e toco em Ir, então vai à metade do livro`() {
        var went: Double? = null
        compose.setContent { KamayoqTheme { GoToContent(TextProgress(0.12f, null), onGo = { went = it }, onStart = {}) } }
        compose.onNodeWithText(context.getString(R.string.reader_book_percent, 12)).assertExists()

        compose.onNodeWithContentDescription(context.getString(R.string.reader_go_slider_book))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithText(context.getString(R.string.reader_book_percent, 50)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_go)).performClick()

        assertEquals(0.5, went!!, 0.001)
    }

    @Test
    fun `dado Ir para numa novel, então ele conta no capítulo e volta ao início do capítulo`() {
        var started = 0
        compose.setContent { KamayoqTheme { GoToContent(TextProgress(0.4f, ChapterPlace(3, 148)), onGo = {}, onStart = { started++ }) } }
        compose.onNodeWithText(context.getString(R.string.reader_chapter_percent, 40)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_go_chapter_start)).performClick()
        assertEquals(1, started)
    }

    @Test
    fun `dado os capítulos da novel no leitor, então cada um diz se foi lido, o atual diz quanto, e tocar abre o capítulo`() {
        val rows = listOf(
            ReaderChapterRow("c1", "Cap. 1", read = true, percent = null),
            ReaderChapterRow("c2", "Cap. 2", read = false, percent = 40),
            ReaderChapterRow("c3", "Cap. 3", read = false, percent = null),
        )
        var opened: String? = null
        compose.setContent { KamayoqTheme { ReaderChaptersContent(rows, currentId = "c2", onOpen = { opened = it }) } }

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.chapter_count, 3, 3)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_chapter_read)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_chapter_reading, 40)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_chapter_unread)).assertExists()
        compose.onNodeWithText("Cap. 3").performClick()

        assertEquals("c3", opened)
    }
}
