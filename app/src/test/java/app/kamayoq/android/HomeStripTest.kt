package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
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
// work on the card moved into the strip, ahead of the cover that was first, the strip, a list keyed by
// work, kept that cover where it was, and the work that came in was cut at the left edge or out of
// sight. With three covers the strip fits the screen and cannot scroll, so the first two tests here
// passed before any fix (runs 45051691215 and 45052242938).
@RunWith(RobolectricTestRunner::class)
class HomeStripTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    // Seven works: the strip holds six covers, wider than the S24's screen, so it can scroll at all.
    private val titles = listOf("Sakamoto Days", "Gantz", "Kaiju No. 8", "Chainsaw Man", "Frieren", "Vagabond", "Dandadan")
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
        titles.forEachIndexed { index, title -> readAgo(title, Duration.ofHours(index + 1L)) }
        items = app.store.home().continueReading
        compose.setContent { KamayoqTheme { ContinueSection(items, onOpenWork = {}, onRead = { _, _ -> }) } }
        compose.waitForIdle()

        // Dandadan, read just now, takes the card, and Sakamoto Days leaves it for the strip's start:
        // Home reads the store again, as back from the reader.
        readAgo("Dandadan", Duration.ofMinutes(1))
        compose.runOnUiThread { items = app.store.home().continueReading }
        compose.waitForIdle()

        assertEquals(listOf("Dandadan", "Sakamoto Days", "Gantz"), items.take(3).map { it.work.title })
        val shown = compose.onAllNodes(hasText("Sakamoto Days"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Sakamoto Days is out of the strip's view", shown.isNotEmpty())
        val left = compose.onNode(hasText("Sakamoto Days"), useUnmergedTree = true).getUnclippedBoundsInRoot().left
        assertTrue("Sakamoto Days starts at $left, cut by the edge", left >= 18.dp)
    }
}
