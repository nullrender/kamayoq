package app.kamayoq.android

import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.PageThemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 5: the text reader's bars and footer (board SinteseLeitorPaginado).
@RunWith(RobolectricTestRunner::class)
class TextReaderChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val book = TextProgress(0.12f, null)
    private val novel = TextProgress(0.19f, ChapterPlace(3, 148))
    private val sepia = PageThemes.colors(PageTheme.SEPIA, systemDark = false)

    private fun withText(text: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(text))))

    @Test
    fun `dado a barra de cima, quando toco em voltar, então volta para a obra, com alvo de 48 dp, e mostra título e lugar no livro`() {
        var back = 0
        compose.setContent { KamayoqTheme { TextReaderTopBar("Dom Casmurro", book, onBack = { back++ }) } }

        compose.onNodeWithContentDescription(context.getString(R.string.reader_back_to_work))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()

        assertEquals(1, back)
        compose.onNodeWithText("Dom Casmurro").assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_book_percent, 12)).assertExists()
    }

    @Test
    fun `dado a barra de baixo de uma novel, então ela diz o capítulo e quanto dele foi lido, e cada botão leva à sua ação`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            KamayoqTheme {
                TextReaderBottomBar(
                    enabled = true,
                    contentsLabel = context.getString(R.string.reader_chapters),
                    progress = novel,
                    onPreviousChapter = { actions += "previous" },
                    onContents = { actions += "contents" },
                    onNextChapter = { actions += "next" },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.reader_chapter_of, 3, 148)).assertExists()
        compose.onNodeWithText(context.getString(R.string.reader_chapter_percent, 19)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_previous_chapter))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(R.string.reader_chapters), useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_next_chapter))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf("previous", "contents", "next"), actions)
    }

    @Test
    fun `dado o primeiro capítulo e o livro ainda abrindo, então o capítulo anterior fica desligado e nada aceita toque`() {
        var asked = 0
        compose.setContent {
            KamayoqTheme {
                TextReaderBottomBar(
                    false, context.getString(R.string.reader_contents), book, onPreviousChapter = null, onContents = {}, onNextChapter = {},
                    onProgress = { asked++ },
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.reader_previous_chapter)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_next_chapter)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.reader_contents), useUnmergedTree = true).performClick()
        // Gemini's review of slice 5, finding 7: the place opened Go to while the book was still opening.
        compose.onNodeWithText(context.getString(R.string.reader_book_percent, 12), useUnmergedTree = true).performClick()
        assertEquals(0, asked)
    }

    @Test
    fun `dado o livro aberto e um próximo capítulo, então o botão dele aceita toque`() {
        compose.setContent {
            KamayoqTheme {
                TextReaderBottomBar(true, context.getString(R.string.reader_contents), book, onPreviousChapter = null, onContents = {}, onNextChapter = {})
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.reader_next_chapter)).assertIsEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_previous_chapter)).assertIsNotEnabled()
    }

    @Test
    fun `dado o rodapé com tudo, então ele mostra o lugar e a hora`() {
        compose.setContent { KamayoqTheme { TextReaderFooter(FooterContent.ALL, novel, sepia) } }
        val place = context.getString(R.string.reader_chapter_of, 3, 148) + " · " + context.getString(R.string.reader_chapter_percent, 19)
        compose.onNodeWithText(place).assertExists()
        val time = Regex("""\d{1,2}:\d{2}""")
        val clock = compose.onAllNodes(SemanticsMatcher("shows the time") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { time.containsMatchIn(it) }
        }).fetchSemanticsNodes()
        assertEquals(1, clock.size)
    }

    @Test
    fun `dado o rodapé só com o progresso, então ele mostra o lugar e não a hora`() {
        compose.setContent { KamayoqTheme { TextReaderFooter(FooterContent.PROGRESS, book, sepia) } }
        compose.onNodeWithText(context.getString(R.string.reader_book_percent, 12)).assertExists()
        val time = Regex("""\d{1,2}:\d{2}""")
        assertEquals(0, compose.onAllNodes(SemanticsMatcher("shows the time") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { time.containsMatchIn(it) }
        }).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado o rodapé desligado, então nada aparece no pé da página`() {
        compose.setContent { KamayoqTheme { TextReaderFooter(FooterContent.NOTHING, book, sepia) } }
        assertEquals(0, withText(context.getString(R.string.reader_book_percent, 12)).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado a barra de baixo, quando toco no lugar, então abre o Ir para`() {
        var asked = 0
        compose.setContent {
            KamayoqTheme {
                TextReaderBottomBar(true, context.getString(R.string.reader_contents), book, onPreviousChapter = null, onContents = {}, onNextChapter = null, onProgress = { asked++ })
            }
        }
        val place = context.getString(R.string.reader_book_percent, 12)
        val goTo = compose.onNodeWithContentDescription(context.getString(R.string.reader_go_to_place, place))
        // Gemini's review of slice 5, finding 11: the description, not it and the texts again.
        assertEquals(null, goTo.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text))
        goTo.assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, asked)
    }

    // Gemini's review of slice 5, finding 7: the bars had a fixed height, so a large system font cut
    // the place under the title.
    @Test
    fun `dado a letra do sistema em dobro, então a barra de cima cresce e o lugar cabe nela`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                KamayoqTheme { Box(Modifier.testTag("bar")) { TextReaderTopBar("Dom Casmurro", book, onBack = {}) } }
            }
        }
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val place = compose.onNodeWithText(context.getString(R.string.reader_book_percent, 12), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("place ends at ${place.bottom}, the bar at ${bar.bottom}", place.bottom <= bar.bottom)
    }

    @Test
    fun `dado o lugar de uma novel na barra de baixo de 360 dp com letra grande, então os dois textos cabem lado a lado`() {
        val long = TextProgress(0.19f, ChapterPlace(928, 1430))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                KamayoqTheme {
                    Box(Modifier.width(360.dp).testTag("bar")) {
                        TextReaderBottomBar(true, context.getString(R.string.reader_chapters), long, onPreviousChapter = {}, onContents = {}, onNextChapter = {}, onProgress = {})
                    }
                }
            }
        }
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val inChapter = compose.onNodeWithText(context.getString(R.string.reader_chapter_percent, 19), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val place = compose.onNodeWithText(context.getString(R.string.reader_chapter_of, 928, 1430), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the place runs into the share: ${place.right} > ${inChapter.left}", place.right <= inChapter.left)
        assertTrue("the share leaves the bar: ${inChapter.right} > ${bar.right}", inChapter.right <= bar.right)
    }
}
