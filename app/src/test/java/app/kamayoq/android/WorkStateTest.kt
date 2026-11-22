package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
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
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import app.kamayoq.data.StoredReadingState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 2 of the v1 interface: the reading state on the Work screen (board SinteseObra).
@RunWith(RobolectricTestRunner::class)
class WorkStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun openWork(title: String) {
        val folder = FolderInMemory(app, listOf(title))
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor(title)
        compose.onNode(hasText(title)).performClick()
        waitFor("Cap. 1")
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    /** Taps a node, scrolling to it first when it sits in a scrolling sheet, as a person would. */
    private fun tap(text: String) {
        val node = hasText(text)
        if (compose.onAllNodes(node and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        compose.onNode(node).performClick()
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun workId(title: String) = app.store.works().single { it.title == title }.id

    /** Saves the work's only chapter read to its end, as the reader does at the end block. */
    private fun readToEnd(title: String) {
        val now = Instant.now()
        val chapter = app.store.chapters(workId(title)).single().id
        app.store.saveProgress(chapter, ChapterProgress(ReadMark(now, ReadMarkSource.READ_TO_END), StripPosition(10, 10, now, "test-device"), 1))
    }

    /** Opens Cap. 1 in the reader, reads it to its end there, and comes back to the Work screen. */
    private fun readInReader(title: String) {
        compose.onNode(hasText("Cap. 1")).performClick()
        compose.waitForIdle()
        readToEnd(title)
        pressBack()
    }

    private fun name(state: ReadingState?) = app.getString(stateName(state))

    private fun stateOf(title: String) = app.store.readingState(app.store.works().single { it.title == title }.id)

    private fun openStates(current: ReadingState?) {
        compose.onNode(hasContentDescription(app.getString(R.string.state_button_label, name(current)))).performClick()
        waitFor(app.getString(R.string.state_sheet_title))
    }

    @Test
    fun `dado a folha de estado aberta, então a alça de arrastar que o TalkBack recebe tem pelo menos 48 por 48 dp`() {
        openWork("Gantz")

        openStates(null)

        // The node TalkBack gets, in the unmerged tree: on the S24 with r686 it was 32 × 4 dp while the
        // merged node, the sheet's box around it, measured 48 dp.
        compose.onNode(hasContentDescription(app.getString(R.string.sheet_handle)), useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `dado uma obra sem estado, quando escolho Quero ler na folha, então o botão diz Quero ler, a folha fecha e o banco guarda`() {
        openWork("Sakamoto Days")
        compose.onNode(hasText(name(null))).assertIsDisplayed()

        openStates(null)
        tap(name(ReadingState.WANT_TO_READ))

        waitUntilGone(app.getString(R.string.state_sheet_title))
        waitFor(name(ReadingState.WANT_TO_READ))
        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, null), stateOf("Sakamoto Days"))
    }

    @Test
    fun `dado Lendo escolhido, quando toco em Desfazer no aviso, então a obra volta a ficar sem estado`() {
        openWork("Sakamoto Days")
        openStates(null)
        tap(name(ReadingState.READING))
        waitFor(app.getString(R.string.state_changed, name(ReadingState.READING)))

        tap(app.getString(R.string.undo))

        waitFor(name(null))
        assertEquals(StoredReadingState.NONE, stateOf("Sakamoto Days"))
    }

    @Test
    fun `dado uma obra em Abandonado, quando escolho Deixar sem estado, então fica sem estado e o aviso diz que os capítulos não mudaram`() {
        openWork("Gantz")
        openStates(null)
        tap(name(ReadingState.DROPPED))
        waitUntilGone(app.getString(R.string.state_sheet_title))
        waitFor(name(ReadingState.DROPPED))

        openStates(ReadingState.DROPPED)
        tap(app.getString(R.string.state_clear))

        waitFor(app.getString(R.string.state_cleared))
        assertEquals(StoredReadingState.NONE, stateOf("Gantz"))
    }

    @Test
    fun `dado uma obra sem estado, quando um capítulo chega ao fim no leitor e ele fecha, então a Obra avisa que mudou sozinho para Lendo e deixa a linha para voltar`() {
        openWork("Sakamoto Days")

        readInReader("Sakamoto Days")

        waitFor(app.getString(R.string.state_auto_notice, name(ReadingState.READING)))
        waitFor(app.getString(R.string.state_changed_by_itself, name(ReadingState.READING)))
        compose.onNode(hasText(app.getString(R.string.state_clear))).assertIsDisplayed()
        assertEquals(ReadingState.READING, stateOf("Sakamoto Days").state)
    }

    @Test
    fun `dado o leitor aberto e fechado pela Obra sem mudar nada, quando o estado muda sozinho depois de sair dela, então reabrir a Obra não avisa`() {
        openWork("Sakamoto Days")
        compose.onNode(hasText("Cap. 1")).performClick()
        compose.waitForIdle()
        pressBack()
        waitFor(app.getString(R.string.back))
        pressBack()
        waitUntilGone(app.getString(R.string.back))

        // Read elsewhere, as from Home: the Work screen keeps its line, and no notice on opening it.
        readToEnd("Sakamoto Days")
        compose.onNode(hasText("Sakamoto Days")).performClick()

        waitFor(app.getString(R.string.state_changed_by_itself, name(ReadingState.READING)))
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText(app.getString(R.string.state_auto_notice, name(ReadingState.READING)))).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado o aviso de que mudou sozinho, quando toco em Desfazer, então a obra volta a ficar sem estado e a linha some`() {
        openWork("Sakamoto Days")
        readInReader("Sakamoto Days")
        waitFor(app.getString(R.string.state_auto_notice, name(ReadingState.READING)))

        tap(app.getString(R.string.undo))

        waitUntilGone(app.getString(R.string.state_changed_by_itself, name(ReadingState.READING)))
        waitFor(name(null))
        assertEquals(StoredReadingState.NONE, stateOf("Sakamoto Days"))
    }

    @Test
    fun `dado uma obra em Quero ler que mudou sozinha para Lendo, quando toco em Voltar a Quero ler na linha, então volta a Quero ler e a linha some`() {
        val folder = FolderInMemory(app, listOf("Gantz"))
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor("Gantz")
        app.store.setReadingState(workId("Gantz"), ReadingState.WANT_TO_READ)
        compose.onNode(hasText("Gantz")).performClick()
        waitFor("Cap. 1")
        readInReader("Gantz")
        val back = app.getString(R.string.state_back_to, name(ReadingState.WANT_TO_READ))
        waitFor(back)

        tap(back)

        waitUntilGone(back)
        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, null), stateOf("Gantz"))
    }

    @Test
    fun `dado todos os capítulos lidos e a obra em Lendo, então a Obra oferece Marcar como Lido, e tocar põe a obra em Lido`() {
        val folder = FolderInMemory(app, listOf("Gantz"))
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor("Gantz")
        readToEnd("Gantz")
        compose.onNode(hasText("Gantz")).performClick()
        waitFor(app.getString(R.string.all_read))

        tap(app.getString(R.string.mark_completed))

        waitFor(name(ReadingState.COMPLETED))
        waitUntilGone(app.getString(R.string.all_read))
        assertEquals(StoredReadingState(ReadingState.COMPLETED, null), stateOf("Gantz"))
    }

    @Test
    fun `dado uma obra lida inteira em andamento, quando o Kavita diz que ela concluiu sem faltar nada, então a Obra diz Publicação concluída e você leu tudo, com Marcar como Lido`() {
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "10|100", 10, "Cap. 1.cbz", 7)
        fun import(status: PublicationStatus) = app.store.importKavita(
            "server-1",
            listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, listOf(chapter), WorkDetails(emptyList(), "", emptyList(), Publication(status, 0)))),
        )
        import(PublicationStatus.ONGOING)
        readToEnd("Gantz")
        // The publication ending is not an event: the state stays Reading (progress contract, section 5).
        import(PublicationStatus.COMPLETED)
        val folder = FolderInMemory(app, emptyList())
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()

        waitFor(app.getString(R.string.all_read_publication_completed))
        compose.onNode(hasText(app.getString(R.string.mark_completed))).assertIsDisplayed()
        assertEquals(ReadingState.READING, stateOf("Gantz").state)
    }

    @Test
    fun `dado uma obra lida inteira com a publicação em andamento, então a Obra diz que você leu todos os capítulos daqui`() {
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "10|100", 10, "Cap. 1.cbz", 7)
        app.store.importKavita(
            "server-1",
            listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, listOf(chapter), WorkDetails(emptyList(), "", emptyList(), Publication(PublicationStatus.ONGOING, 0)))),
        )
        readToEnd("Gantz")
        val folder = FolderInMemory(app, emptyList())
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor("Gantz")
        compose.onNode(hasText("Gantz")).performClick()

        waitFor(app.getString(R.string.all_read))
    }
}
