package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: Home's Continue reading (board SinteseInicio; FEATURES, part 2).
@RunWith(RobolectricTestRunner::class)
class HomeContinueTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val titles = listOf("Sakamoto Days", "Gantz")
    private val folder = FolderInMemory(app, titles)

    /** The folder as a first reading left it, days ago: its zero mark. */
    private fun folderRead() {
        val paths = titles.map { "Comics/$it/Cap. 1.cbz" }
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(paths), paths.associateWith { FileStamp(10, 1) }, at = Instant.now().minus(Duration.ofDays(30)))
    }

    /** [title] read up to page 5 of 20 of its Cap. 1, [ago] before now, and in Reading. */
    private fun readAgo(title: String, ago: Duration, state: ReadingState? = ReadingState.READING) {
        val work = app.store.works().single { it.title == title }.id
        val chapter = app.store.chapters(work).single().id
        app.store.saveProgress(chapter, ChapterProgress(null, StripPosition(5, 20, Instant.now().minus(ago), "test-device"), 0))
        app.store.setReadingState(work, state)
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

    private val heading get() = app.getString(R.string.home_continue_title)
    private val continueCap1 get() = app.getString(R.string.home_continue, "Cap. 1")

    @Test
    fun `dado duas obras em Lendo, quando abro o Início, então a lida há 2 h está no cartão com o lugar, o tempo e Continuar, e a outra na faixa`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130))
        readAgo("Gantz", Duration.ofHours(30))
        showHome()

        waitFor(heading)

        waitFor(continueCap1)
        assertEquals(1, count(continueCap1))
        assertEquals(1, count(app.getString(R.string.home_place, "Cap. 1", 25)))
        assertEquals(1, count(app.resources.getQuantityString(R.plurals.elapsed_hours, 2, 2)))
        assertEquals(1, count("Sakamoto Days"))
        assertEquals(1, count("Gantz"))
    }

    @Test
    fun `dado uma obra lida pela última vez há 20 dias, então o cartão diz onde a pessoa deixou, sem dizer há quanto tempo`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofDays(20))
        showHome()

        waitFor(app.getString(R.string.home_where_left, "Cap. 1"))

        assertEquals(0, count(app.resources.getQuantityString(R.plurals.elapsed_days, 20, 20)))
    }

    @Test
    fun `dado o cartão, quando toco Continuar e volto do leitor, então volto ao Início e não à Obra`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130))
        showHome()
        waitFor(continueCap1)

        compose.onNode(hasText(continueCap1) and hasClickAction()).performClick()
        compose.waitForIdle()
        pressBack()

        waitFor(heading)
        assertEquals(0, count(app.getString(R.string.back)))
    }

    @Test
    fun `dado uma gravação do leitor ainda na fila quando ele fecha, então o Início mostra o lugar que ela grava`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130))
        showHome()
        waitFor(continueCap1)
        compose.onNode(hasText(continueCap1) and hasClickAction()).performClick()
        compose.waitForIdle()

        // The reader's last page, saved in its turn and slower than the way back to Home. Put in line
        // off the main thread: the reader's own turn ends there, and the test runs on it.
        val chapter = app.store.chapters(app.store.works().single { it.title == "Sakamoto Days" }.id).single().id
        val queued = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            val round = app.opening.turns.open { it }
            app.opening.turns.inLine(round) {
                Thread.sleep(1_500)
                app.store.saveProgress(chapter, ChapterProgress(null, StripPosition(10, 20, Instant.now(), "test-device"), 0))
            }
            queued.complete(Unit)
        }
        compose.waitUntil(10_000) { queued.isCompleted }
        pressBack()

        waitFor(app.getString(R.string.home_place, "Cap. 1", 50))
    }

    @Test
    fun `dado o cartão, então todo botão dele tem um nome para o TalkBack`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130))
        showHome()
        waitFor(continueCap1)

        val unnamed = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
                node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
        }
        assertEquals(0, unnamed.size)
    }

    @Test
    fun `dado o cartão, quando toco no título da obra, então abre a página da Obra`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130))
        showHome()
        waitFor(continueCap1)

        compose.onNodeWithText("Sakamoto Days").performClick()

        waitFor(app.getString(R.string.back))
    }

    @Test
    fun `dado uma obra lida mas fora de Lendo, então o Início não mostra Continuar lendo`() {
        folderRead()
        readAgo("Sakamoto Days", Duration.ofMinutes(130), state = ReadingState.ON_HOLD)
        showHome()

        // Home read and empty, not still being read.
        waitFor(app.getString(R.string.home_empty_title))
        assertEquals(0, count(heading))
    }
}
