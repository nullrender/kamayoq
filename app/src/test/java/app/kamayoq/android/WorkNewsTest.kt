package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: "New · 2 h ago" on the Work screen and the count of new chapters in
// the Library (reader-design FEATURES, part 2: new is what arrived since the work's page was opened).
@RunWith(RobolectricTestRunner::class)
class WorkNewsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val folder = FolderInMemory(app, listOf("Sakamoto Days")).apply {
        moreChapters = mapOf("Sakamoto Days" to listOf("Cap. 2.cbz"))
    }

    /** The folder read once with Cap. 1 only, days ago, and again with Cap. 2, about two hours ago. */
    private fun cap2ArrivedTwoHoursAgo() {
        val source = folder.tree.toString()
        val first = listOf("Comics/Sakamoto Days/Cap. 1.cbz")
        val both = first + "Comics/Sakamoto Days/Cap. 2.cbz"
        val now = Instant.now()
        app.store.importLocal(source, FolderConvention.read(first), first.associateWith { FileStamp(10, 1) }, at = now.minus(Duration.ofDays(3)))
        app.store.importLocal(source, FolderConvention.read(both), both.associateWith { FileStamp(10, 1) }, at = now.minus(Duration.ofMinutes(130)))
    }

    private fun show() {
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        compose.goToLibrary(app)
        waitFor("Sakamoto Days")
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun count(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private val newTwoHoursAgo get() = app.getString(R.string.chapter_new, app.resources.getQuantityString(R.plurals.elapsed_hours, 2, 2))

    private fun openWork() {
        compose.onNodeWithText("Sakamoto Days").performClick()
        waitFor("Cap. 2")
    }

    @Test
    fun `dado um capítulo que chegou há 2 h, quando abro a Obra, então só ele diz que é novo e há quanto tempo chegou`() {
        cap2ArrivedTwoHoursAgo()
        show()

        openWork()

        waitFor(newTwoHoursAgo)
        assertEquals(1, count(newTwoHoursAgo))
    }

    @Test
    fun `dado a Obra aberta uma vez, quando volto e a abro de novo, então o capítulo não diz mais que é novo`() {
        cap2ArrivedTwoHoursAgo()
        show()
        openWork()
        waitFor(newTwoHoursAgo)

        pressBack()
        waitFor("Sakamoto Days")
        openWork()
        compose.waitForIdle()

        assertEquals(0, count(newTwoHoursAgo))
    }

    @Test
    fun `dado a Obra aberta, quando leio um capítulo e volto à Obra, então o novo continua dizendo que é novo`() {
        cap2ArrivedTwoHoursAgo()
        show()
        openWork()
        waitFor(newTwoHoursAgo)

        compose.onNodeWithText("Cap. 1").performClick()
        compose.waitForIdle()
        pressBack()
        waitFor("Cap. 2")
        compose.waitForIdle()

        assertEquals(1, count(newTwoHoursAgo))
    }

    @Test
    fun `dado um capítulo novo numa obra, então a linha dela na Biblioteca diz quantos são novos, e depois de abrir a Obra não diz mais`() {
        cap2ArrivedTwoHoursAgo()
        val oneNew = app.resources.getQuantityString(R.plurals.new_count, 1, 1)
        show()

        waitFor(oneNew, substring = true)
        openWork()
        pressBack()
        waitFor("Sakamoto Days")

        // The Library is read again off the main thread, which waitForIdle does not wait for.
        compose.waitUntil(10_000) { count(oneNew, substring = true) == 0 }
    }
}
