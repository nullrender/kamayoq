package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.data.ContinueItem
import app.kamayoq.core.library.FileStamp
import app.kamayoq.data.home
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Defect of the S24 test of r745 (ui-slice-4-s24-plan-2026-11-21.md, "Resultado na r745"): when the
// work on the card moved into the strip, ahead of the cover that was first, the strip came back from
// the reader anchored on that cover, saved by its key, and the work that came in was cut at the left
// edge or out of sight. Without a remade screen the strip shows its start (run 45051691215).
@RunWith(RobolectricTestRunner::class)
class HomeStripTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val titles = listOf("Sakamoto Days", "Gantz", "Kaiju No. 8", "Dandadan")
    private val folder = FolderInMemory(app, titles)

    private var items by mutableStateOf<List<ContinueItem>>(emptyList())

    private fun readAgo(title: String, ago: Duration) {
        val work = app.store.works().single { it.title == title }.id
        val chapter = app.store.chapters(work).single().id
        app.store.saveProgress(chapter, ChapterProgress(null, StripPosition(5, 20, Instant.now().minus(ago), "test-device"), 0))
        app.store.setReadingState(work, ReadingState.READING)
    }

    @Test
    fun `dado a obra do cartão que passa para a faixa, à frente da primeira capa, então a faixa mostra o começo, com ela inteira na borda`() {
        val paths = titles.map { "Comics/$it/Cap. 1.cbz" }
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(paths), paths.associateWith { FileStamp(10, 1) }, at = Instant.now().minus(Duration.ofDays(30)))
        readAgo("Sakamoto Days", Duration.ofHours(1))
        readAgo("Gantz", Duration.ofHours(2))
        readAgo("Kaiju No. 8", Duration.ofHours(3))
        readAgo("Dandadan", Duration.ofHours(4))
        items = app.store.home().continueReading
        val screen = StateRestorationTester(compose)
        screen.setContent { KamayoqTheme { ContinueSection(items, onOpenWork = {}, onRead = { _, _ -> }) } }
        compose.waitForIdle()

        // Away in the reader, Dandadan, read just now, takes the card, and Sakamoto Days leaves it for
        // the strip's start. Back on Home the screen is made again from its saved state.
        readAgo("Dandadan", Duration.ofMinutes(1))
        compose.runOnUiThread { items = app.store.home().continueReading }
        screen.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf("Dandadan", "Sakamoto Days", "Gantz", "Kaiju No. 8"), items.map { it.work.title })
        val shown = compose.onAllNodes(hasText("Sakamoto Days"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Sakamoto Days is out of the strip's view", shown.isNotEmpty())
        val left = compose.onNode(hasText("Sakamoto Days"), useUnmergedTree = true).getUnclippedBoundsInRoot().left
        assertTrue("Sakamoto Days starts at $left, cut by the edge", left >= 18.dp)
    }
}
