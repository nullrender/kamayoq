package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ReadingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 2 of the v1 interface: the Library's chips by reading state (board SinteseBiblioteca).
@RunWith(RobolectricTestRunner::class)
class LibraryStateChipsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun showLibrary(vararg titles: String) {
        val folder = FolderInMemory(app, titles.toList())
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        titles.forEach(::waitFor)
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun tap(text: String) {
        val node = hasText(text)
        if (compose.onAllNodes(node and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        compose.onNode(node).performClick()
    }

    private fun name(state: ReadingState?) = app.getString(stateName(state))

    /** Chooses [state] for the work [title] on its Work screen, as a person would, and comes back. */
    private fun choose(title: String, state: ReadingState) {
        tap(title)
        waitFor("Cap. 1")
        compose.onNode(hasContentDescription(app.getString(R.string.state_button_label, name(null)))).performClick()
        waitFor(app.getString(R.string.state_sheet_title))
        tap(name(state))
        waitFor(name(state))
        tap(app.getString(R.string.back))
        waitFor(app.getString(R.string.library_title))
    }

    @Test
    fun `dado nenhuma obra com estado, então a Biblioteca não mostra os chips de estado`() {
        showLibrary("Gantz", "Sakamoto Days")

        compose.onNode(hasText(app.getString(R.string.state_all))).assertDoesNotExist()
    }

    @Test
    fun `dado uma obra posta em Lendo na Obra, quando volto e toco no chip Lendo, então só ela fica, e Todas traz as duas de volta`() {
        showLibrary("Gantz", "Sakamoto Days")
        choose("Gantz", ReadingState.READING)
        waitFor(app.getString(R.string.state_all))

        tap(name(ReadingState.READING))

        waitUntilGone("Sakamoto Days")
        waitFor("Gantz")
        tap(app.getString(R.string.state_all))
        waitFor("Sakamoto Days")
    }

    @Test
    fun `dado o chip Em pausa sem obras, quando toco nele, então a lista diz que nenhuma obra tem esses filtros e Limpar filtros volta a todas`() {
        showLibrary("Gantz", "Sakamoto Days")
        choose("Gantz", ReadingState.READING)
        waitFor(app.getString(R.string.state_all))

        tap(name(ReadingState.ON_HOLD))

        waitFor(app.getString(R.string.filter_empty))
        waitUntilGone("Gantz")
        tap(app.getString(R.string.filter_clear))
        waitFor("Gantz")
        waitFor("Sakamoto Days")
    }
}
