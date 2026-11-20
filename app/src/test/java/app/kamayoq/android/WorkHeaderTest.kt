package app.kamayoq.android

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.library.WorkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 3 of the v1 interface: the Work screen's header (board SinteseObra), from what Kavita says.
@RunWith(RobolectricTestRunner::class)
class WorkHeaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val gantz = WorkDetails(
        authors = listOf("Hiroya Oku"),
        summary = "Kei e Masaru morrem atropelados no metrô.",
        tags = listOf("Ação", "Ficção científica"),
        publication = Publication(PublicationStatus.COMPLETED, 9),
    )

    private fun kavitaWork(title: String, details: WorkDetails) {
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "3|100", 3, "Cap. 1.cbz", 7)
        app.store.importKavita("server-1", listOf(ImportedWork("12", title, WorkType.COMICS, null, 2, listOf(chapter), details)))
    }

    private fun open(title: String, local: List<String> = emptyList()) {
        val folder = FolderInMemory(app, local)
        compose.setContent {
            LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores, app.covers)
        }
        waitFor(title)
        compose.onNode(hasText(title)).performClick()
        waitFor("Cap. 1")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `dado uma obra do Kavita com dados, quando abro a Obra, então vejo autor, situação com o que falta, sinopse e etiquetas`() {
        kavitaWork("Gantz", gantz)

        open("Gantz")

        waitFor("Hiroya Oku")
        val completed = app.getString(R.string.publication_completed)
        compose.onNode(hasText(app.resources.getQuantityString(R.plurals.publication_missing, 9, completed, 9))).assertIsDisplayed()
        compose.onNode(hasText(gantz.summary)).assertIsDisplayed()
        compose.onNode(hasText("Ação")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Ficção científica")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `dado a publicação concluída sem nada faltando, então a linha diz só Concluída`() {
        kavitaWork("Gantz", gantz.copy(publication = Publication(PublicationStatus.COMPLETED, 0)))

        open("Gantz")

        waitFor(app.getString(R.string.publication_completed))
        // The words after the number, the same in each count.
        val missing = app.resources.getQuantityString(R.plurals.publication_missing, 9, "", 9).substringAfter("9").trim()
        assertTrue(!exists(missing))
    }

    @Test
    fun `dado uma obra local sem dados, quando abro a Obra, então vejo o tipo e a capa provisória, sem situação nem Mais`() {
        open("Sakamoto Days", local = listOf("Sakamoto Days"))

        waitFor(app.getString(R.string.section_comics))
        waitForTag(COVER_PROVISIONAL)
        for (status in listOf(R.string.publication_ongoing, R.string.publication_hiatus, R.string.publication_completed, R.string.publication_cancelled)) {
            assertTrue(app.getString(status), !exists(app.getString(status)))
        }
        assertTrue(!exists(app.getString(R.string.summary_more)))
    }

    @Test
    fun `dado uma sinopse longa, quando toco em Mais, então ela abre e o botão passa a Menos`() {
        kavitaWork("Gantz", gantz.copy(summary = (1..12).joinToString("\n") { "Parágrafo $it da sinopse." }))
        open("Gantz")
        waitFor(app.getString(R.string.summary_more))

        compose.onNode(hasText(app.getString(R.string.summary_more))).performScrollTo().performClick()

        waitFor(app.getString(R.string.summary_less))
    }

    @Test
    fun `dado a capa guardada, quando abro a Obra, então ela aparece no lugar da provisória`() {
        kavitaWork("Gantz", gantz)
        val workId = app.store.works().single { it.title == "Gantz" }.id
        app.covers.keep(workId, Base64.decode(ONE_PIXEL_PNG, Base64.DEFAULT))

        open("Gantz")

        waitForTag(COVER_IMAGE)
    }

    @Test
    fun `dado a Obra, então o botão de estado fica a pelo menos 16 dp abaixo da capa, sem encostar nela`() {
        open("Sakamoto Days", local = listOf("Sakamoto Days"))
        waitForTag(COVER_PROVISIONAL)

        val cover = compose.onNode(hasTestTag(COVER_PROVISIONAL)).fetchSemanticsNode().boundsInRoot
        val state = compose.onNode(hasContentDescription(app.getString(R.string.state_button_label, app.getString(stateName(null)))))
            .fetchSemanticsNode().boundsInRoot

        assertTrue(state.top - cover.bottom >= with(compose.density) { 16.dp.toPx() })
    }

    @Test
    fun `dado uma sinopse longa, então Mais começa na mesma margem que o texto da sinopse`() {
        kavitaWork("Gantz", gantz.copy(summary = (1..12).joinToString("\n") { "Parágrafo $it da sinopse." }))
        open("Gantz")
        waitFor(app.getString(R.string.summary_more))

        val summary = compose.onNode(hasText("Parágrafo 1", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val more = compose.onNode(hasText(app.getString(R.string.summary_more)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertEquals(summary.left, more.left, 1f)
    }

    @Test
    fun `dado a Obra antes de a obra carregar, então a lista não mostra nada, e o cabeçalho será o primeiro item a aparecer`() {
        // On the S24 with r641 a work opened scrolled past its header: the list's first frame had the
        // state button first, and when the header came in above it the list kept that button in place.
        compose.setContent {
            WorkScreen(
                store = app.store,
                covers = null,
                kavitaSync = null,
                workId = "a-work-not-loaded",
                listState = rememberLazyListState(),
                progressVersion = 0,
                readingSince = null,
                onNoticed = {},
                onOpenChapter = {},
                onBack = {},
            )
        }
        compose.waitForIdle()

        val state = app.getString(R.string.state_button_label, app.getString(stateName(null)))
        assertEquals(0, compose.onAllNodes(hasContentDescription(state)).fetchSemanticsNodes().size)
    }

    private companion object {
        const val ONE_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
