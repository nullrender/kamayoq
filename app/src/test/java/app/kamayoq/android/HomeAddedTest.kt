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

// Slice 4 of the v1 interface: Home's Added (board SinteseInicio; his answer of 21/11/2026: each
// work until its page is opened, the ten newest).
@RunWith(RobolectricTestRunner::class)
class HomeAddedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val folder = FolderInMemory(app, listOf("Sakamoto Days", "Tower of God"))

    private fun read(at: Instant, vararg titles: String) {
        val paths = titles.map { "Comics/$it/Cap. 1.cbz" }
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(paths), paths.associateWith { FileStamp(10, 1) }, at = at)
    }

    /** The folder read days ago with one work, and Tower of God in it about two hours ago. */
    private fun towerAdded() {
        read(Instant.now().minus(Duration.ofDays(3)), "Sakamoto Days")
        read(Instant.now().minus(Duration.ofMinutes(130)), "Sakamoto Days", "Tower of God")
    }

    private fun showHome() {
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor(app.getString(R.string.nav_home))
        compose.onNodeWithText(app.getString(R.string.nav_home)).performClick()
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private val heading get() = app.getString(R.string.home_added_title)

    @Test
    fun `dado uma obra nova na pasta, então Adicionadas a mostra com o tipo, os capítulos e quando chegou`() {
        towerAdded()
        showHome()

        waitFor(heading)

        val line = app.getString(
            R.string.home_added_line,
            app.getString(R.string.section_comics),
            app.resources.getQuantityString(R.plurals.chapter_count, 1, 1),
            app.resources.getQuantityString(R.plurals.elapsed_hours, 2, 2),
        )
        waitFor(line)
        assertEquals(1, count("Tower of God"))
        assertEquals(0, count("Sakamoto Days"))
    }

    @Test
    fun `dado uma obra em Adicionadas, quando a abro e volto, então ela saiu de Adicionadas`() {
        towerAdded()
        showHome()
        waitFor(heading)

        compose.onNodeWithText("Tower of God").performClick()
        waitFor(app.getString(R.string.back))
        pressBack()

        // Home is read again off the main thread, which waitForIdle does not wait for.
        waitFor(app.getString(R.string.nav_home))
        compose.waitUntil(10_000) { count(heading) == 0 }
    }
}
