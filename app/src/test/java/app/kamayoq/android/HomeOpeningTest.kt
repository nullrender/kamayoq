package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: the app opens on Home, Back from the other places goes there, and an
// empty Home says why (FEATURES, parts 1 and 2; decision of 19/11/2026).
@RunWith(RobolectricTestRunner::class)
class HomeOpeningTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun show(folder: FolderInMemory) {
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun tab(label: Int) = compose.onNode(hasText(app.getString(label)) and isSelectable())

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `dado uma obra em Lendo, quando o app abre, então abre no Início com Continuar lendo`() {
        val folder = FolderInMemory(app, listOf("Gantz"))
        val path = "Comics/Gantz/Cap. 1.cbz"
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(listOf(path)), mapOf(path to FileStamp(10, 1)), at = Instant.now().minus(Duration.ofDays(3)))
        val work = app.store.works().single().id
        app.store.saveProgress(app.store.chapters(work).single().id, ChapterProgress(null, StripPosition(5, 20, Instant.now(), "test-device"), 0))
        app.store.setReadingState(work, ReadingState.READING)

        show(folder)

        waitFor(app.getString(R.string.home_continue_title))
        tab(R.string.nav_home).assertIsSelected()
    }

    @Test
    fun `dado obras na pasta e nada lido, quando o app abre, então o Início diz por que está vazio`() {
        show(FolderInMemory(app, listOf("Gantz")))

        waitFor(app.getString(R.string.home_empty_title))
        tab(R.string.nav_home).assertIsSelected()
        assertEquals(1, count(app.getString(R.string.home_go_library)))
    }

    @Test
    fun `dado a Biblioteca, quando aperto Voltar, então vou ao Início`() {
        show(FolderInMemory(app, listOf("Gantz")))
        compose.goToLibrary(app)
        waitFor("Gantz")

        pressBack()

        waitFor(app.getString(R.string.home_empty_title))
        tab(R.string.nav_home).assertIsSelected()
    }

    @Test
    fun `dado uma pasta escolhida sem nenhuma obra, então o Início mostra os caminhos de entrada`() {
        show(FolderInMemory(app, emptyList()))

        waitFor(app.getString(R.string.source_files))
        waitFor(app.getString(R.string.kavita_connect))
        tab(R.string.nav_home).assertIsSelected()
    }
}
