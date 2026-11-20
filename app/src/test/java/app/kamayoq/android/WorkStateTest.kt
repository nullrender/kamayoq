package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ReadingState
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

    private fun name(state: ReadingState?) = app.getString(stateName(state))

    private fun stateOf(title: String) = app.store.readingState(app.store.works().single { it.title == title }.id)

    private fun openStates(current: ReadingState?) {
        compose.onNode(hasContentDescription(app.getString(R.string.state_button_label, name(current)))).performClick()
        waitFor(app.getString(R.string.state_sheet_title))
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
}
