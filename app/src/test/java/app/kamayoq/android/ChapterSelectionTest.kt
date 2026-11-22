package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 3 of the v1 interface: marking chapters read and unread on the Work screen, one or several.
@RunWith(RobolectricTestRunner::class)
class ChapterSelectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun openGantz(count: Int = 3) {
        val chapters = (1..count).map {
            ImportedChapter("9$it", "Cap. $it", UnitKind.CHAPTER, it.toDouble(), ChapterFormat.ARCHIVE, "20|100", 20, "Cap. $it.cbz", 7)
        }
        app.store.importKavita("server-1", listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, chapters)))
        val folder = FolderInMemory(app, emptyList())
        compose.setContent {
            LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores, app.covers)
        }
        compose.goToLibrary(app)
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()
        waitFor("Cap. 1")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun chapterIds() = app.store.chapters(app.store.works().single().id).map { it.id }

    private fun readMarks() = chapterIds().map { app.store.progress(it).readMark?.source }

    private fun selected(count: Int) = app.resources.getQuantityString(R.plurals.selection_count, count, count)

    private fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().positionInRoot.y

    private fun openMenu() = compose.onNode(hasContentDescription(app.getString(R.string.more_options))).performClick()

    @Test
    fun `dado a Obra, quando seguro o dedo num capítulo e toco em Lidos, então ele fica lido pela pessoa e o aviso diz quantos`() {
        openGantz()

        compose.onNode(hasText("Cap. 2")).performTouchInput { longClick() }
        waitFor(selected(1))
        compose.onNode(hasText(app.getString(R.string.selection_read))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.marked_read, 1, 1))
        assertEquals(listOf(null, ReadMarkSource.PERSON, null), readMarks())
        waitUntilGone(selected(1))
    }

    @Test
    fun `dado capítulos marcados como lidos, quando toco em Desfazer no aviso, então voltam a não lidos`() {
        openGantz()
        compose.onNode(hasText("Cap. 1")).performTouchInput { longClick() }
        compose.onNode(hasText(app.getString(R.string.selection_read))).performClick()
        waitFor(app.resources.getQuantityString(R.plurals.marked_read, 1, 1))

        compose.onNode(hasText(app.getString(R.string.undo))).performClick()

        compose.waitUntil(10_000) { readMarks() == listOf(null, null, null) }
    }

    @Test
    fun `dado o menu, quando escolho Marcar tudo como lido, então os três ficam lidos e cada capítulo diz Lido`() {
        openGantz()

        openMenu()
        compose.onNode(hasText(app.getString(R.string.mark_all_read))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.marked_read, 3, 3))
        assertEquals(List(3) { ReadMarkSource.PERSON }, readMarks())
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(app.getString(R.string.chapter_read))).fetchSemanticsNodes().size == 3 }
    }

    @Test
    fun `dado dois dos três capítulos já lidos, quando escolho Marcar tudo como lido, então o aviso conta só o que mudou`() {
        openGantz()
        app.store.markRead(app.store.works().single().id, chapterIds().take(2), Instant.now())

        openMenu()
        compose.onNode(hasText(app.getString(R.string.mark_all_read))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.marked_read, 1, 1))
    }

    @Test
    fun `dado todos os capítulos já lidos, quando escolho Marcar tudo como lido, então o aviso diz que nada mudou e não oferece Desfazer`() {
        openGantz()
        app.store.markRead(app.store.works().single().id, chapterIds(), Instant.now())

        openMenu()
        compose.onNode(hasText(app.getString(R.string.mark_all_read))).performClick()

        waitFor(app.getString(R.string.marked_nothing))
        assertTrue(compose.onAllNodes(hasText(app.getString(R.string.undo))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `dado o menu, quando escolho Selecionar capítulos, toco em dois lidos e em Não lidos, então os dois ficam não lidos`() {
        val now = Instant.now()
        openGantz()
        app.store.markRead(app.store.works().single().id, chapterIds(), now)

        openMenu()
        compose.onNode(hasText(app.getString(R.string.select_chapters))).performClick()
        waitFor(app.getString(R.string.selection_none))
        compose.onNode(hasText("Cap. 1")).performClick()
        compose.onNode(hasText("Cap. 3")).performClick()
        waitFor(selected(2))
        compose.onNode(hasText(app.getString(R.string.selection_unread))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.marked_unread, 2, 2))
        assertEquals(listOf(null, ReadMarkSource.PERSON, null), readMarks())
    }

    @Test
    fun `dado um capítulo só selecionado, quando toco em Marcar os anteriores como lidos, então os de antes ficam lidos e ele não`() {
        openGantz()

        compose.onNode(hasText("Cap. 3")).performTouchInput { longClick() }
        compose.onNode(hasText(app.getString(R.string.mark_previous_read, 2))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.marked_previous, 2, 2))
        assertEquals(listOf(ReadMarkSource.PERSON, ReadMarkSource.PERSON, null), readMarks())
    }

    @Test
    fun `dado a seleção aberta, quando aperto Voltar, então ela fecha, a Obra continua e nada é marcado`() {
        openGantz()
        compose.onNode(hasText("Cap. 2")).performTouchInput { longClick() }
        waitFor(selected(1))

        pressBack()

        waitUntilGone(selected(1))
        compose.onNode(hasText("Cap. 2")).assertIsDisplayed()
        assertEquals(listOf(null, null, null), readMarks())
    }

    @Test
    fun `dado um capítulo lido até a metade, então a lista diz Lendo com a porcentagem`() {
        val now = Instant.now()
        openGantz()

        app.store.saveProgress(chapterIds()[1], ChapterProgress(null, StripPosition(7, 20, now, "test-device"), 0))
        compose.onNode(hasContentDescription(app.getString(R.string.more_options))).assertIsDisplayed()
        // Leaving and coming back reads the progress again.
        pressBack()
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()

        waitFor(app.getString(R.string.chapter_reading, 35))
    }

    @Test
    fun `dado a Obra, quando seguro o dedo num capítulo, então ele fica sob o dedo e as ações da seleção aparecem no pé da tela`() {
        openGantz()
        val before = top("Cap. 2")

        compose.onNode(hasText("Cap. 2")).performTouchInput { longClick() }
        waitFor(selected(1))

        assertEquals(before, top("Cap. 2"), 1f)
        assertTrue(top(app.getString(R.string.selection_read)) > top("Cap. 3"))
        assertTrue(top(selected(1)) > top("Cap. 3"))
    }

    @Test
    fun `dado uma obra nunca aberta, quando marco os dois primeiros como lidos, então o botão continua no terceiro, não em Começar a ler`() {
        openGantz()
        compose.onNode(hasText(app.getString(R.string.start_reading))).assertIsDisplayed()

        openMenu()
        compose.onNode(hasText(app.getString(R.string.select_chapters))).performClick()
        compose.onNode(hasText("Cap. 1")).performClick()
        compose.onNode(hasText("Cap. 2")).performClick()
        compose.onNode(hasText(app.getString(R.string.selection_read))).performClick()

        waitFor(app.getString(R.string.continue_reading, "Cap. 3"))
    }

    @Test
    fun `dado um capítulo na metade de baixo da tela, quando seguro o dedo nele, então ele fica visível acima da barra da seleção`() {
        openGantz(count = 12)
        val pressed = (12 downTo 1).map { "Cap. $it" }.first { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText(pressed)).performTouchInput { longClick() }
        waitFor(selected(1))
        compose.waitForIdle()

        val bar = compose.onNode(hasTestTag(SELECTION_BAR)).fetchSemanticsNode().boundsInRoot
        val chapter = compose.onNode(hasText(pressed)).fetchSemanticsNode().boundsInRoot
        assertTrue("$pressed ends at ${chapter.bottom}, the bar starts at ${bar.top}", chapter.bottom <= bar.top + 1)
    }

    @Test
    fun `dado a leitura parada no capítulo 2, quando marco do 1 ao 3 como lidos, então o botão continua no 4, o primeiro não lido`() {
        openGantz(count = 5)
        app.store.saveProgress(chapterIds()[1], ChapterProgress(null, StripPosition(3, 20, Instant.now(), "test-device"), 0))
        // Leaving and coming back reads the progress again.
        pressBack()
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()
        waitFor(app.getString(R.string.continue_reading, "Cap. 2"))

        openMenu()
        compose.onNode(hasText(app.getString(R.string.select_chapters))).performClick()
        for (chapter in listOf("Cap. 1", "Cap. 2", "Cap. 3")) compose.onNode(hasText(chapter)).performClick()
        compose.onNode(hasText(app.getString(R.string.selection_read))).performClick()

        waitFor(app.getString(R.string.continue_reading, "Cap. 4"))
    }

    @Test
    fun `dado todos lidos e uma releitura parada no meio do capítulo 2, então o botão continua no 2, não no último`() {
        openGantz()
        val ids = chapterIds()
        val read = Instant.parse("2026-11-20T12:00:00Z")
        for (id in ids) app.store.saveProgress(id, ChapterProgress(ReadMark(read, ReadMarkSource.READ_TO_END), StripPosition(20, 20, read, "test-device"), 1))
        // Reopened later and left halfway: the read mark stays, the position is the reread's.
        val reread = Instant.parse("2026-11-20T13:00:00Z")
        app.store.saveProgress(ids[1], ChapterProgress(ReadMark(read, ReadMarkSource.READ_TO_END), StripPosition(7, 20, reread, "test-device"), 1))
        // Leaving and coming back reads the progress again.
        pressBack()
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()

        waitFor(app.getString(R.string.continue_reading, "Cap. 2"))
    }

    @Test
    fun `dado um capítulo lido até o fim e desmarcado, então a lista não diz Lendo 100 por cento`() {
        openGantz()
        val first = chapterIds().first()
        val read = Instant.parse("2026-11-20T12:00:00Z")
        app.store.saveProgress(first, ChapterProgress(ReadMark(read, ReadMarkSource.READ_TO_END), StripPosition(20, 20, read, "test-device"), 1))
        app.store.markUnread(app.store.works().single().id, listOf(first), Instant.now())
        // Leaving and coming back reads the progress again.
        pressBack()
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()
        waitFor("Cap. 1")

        assertEquals(0, compose.onAllNodes(hasText(app.getString(R.string.chapter_reading, 100))).fetchSemanticsNodes().size)
    }
}
