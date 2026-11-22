package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: a conflict between two chapters is the person's to settle before the
// reader opens (progress contract, case 5), from Home too.
@RunWith(RobolectricTestRunner::class)
class HomeConflictTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val folder = FolderInMemory(app, listOf("Gantz")).apply { moreChapters = mapOf("Gantz" to listOf("Cap. 2.cbz")) }

    /** Gantz read here up to page 5 of Cap. 1, and Kavita's reading in Cap. 2 kept aside as the other choice. */
    private fun crossingChapters() {
        val paths = listOf("Comics/Gantz/Cap. 1.cbz", "Comics/Gantz/Cap. 2.cbz")
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(paths), paths.associateWith { FileStamp(10, 1) }, at = Instant.now().minus(Duration.ofDays(30)))
        val work = app.store.works().single().id
        val (cap1, cap2) = app.store.chapters(work).map { it.id }
        val now = Instant.now()
        app.store.saveProgress(cap2, ChapterProgress(null, StripPosition(8, 20, now.minus(Duration.ofHours(3)), "kavita"), 0))
        app.store.saveProgress(cap1, ChapterProgress(null, StripPosition(5, 20, now.minus(Duration.ofHours(2)), "test-device"), 0))
        app.store.setReadingState(work, ReadingState.READING)
        app.store.settleResume(cap2, localMoved = true, before = null)
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `dado um conflito entre dois capítulos, quando toco Continuar no Início, então abre a Obra com as duas escolhas`() {
        crossingChapters()
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor(app.getString(R.string.nav_home))
        compose.onNodeWithText(app.getString(R.string.nav_home)).performClick()
        val continueCap1 = app.getString(R.string.home_continue, "Cap. 1")
        waitFor(continueCap1)

        compose.onNode(hasText(continueCap1) and hasClickAction()).performClick()

        waitFor(app.getString(R.string.conflict_title))
        waitFor(app.getString(R.string.conflict_kavita, "Cap. 2"))
    }
}
