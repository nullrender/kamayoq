package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.data.kavita.KavitaClient
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: Home's Arrived (board SinteseInicio; FEATURES, part 2).
@RunWith(RobolectricTestRunner::class)
class HomeArrivedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val folder = FolderInMemory(app, listOf("Sakamoto Days")).apply {
        moreChapters = mapOf("Sakamoto Days" to listOf("Cap. 2.cbz", "Cap. 3.cbz"))
    }

    private val cap1 = "Comics/Sakamoto Days/Cap. 1.cbz"
    private val cap2 = "Comics/Sakamoto Days/Cap. 2.cbz"
    private val cap3 = "Comics/Sakamoto Days/Cap. 3.cbz"

    private fun read(at: Instant, vararg paths: String) =
        app.store.importLocal(folder.tree.toString(), FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(10, 1) }, at = at)

    /** The folder read days ago with Cap. 1, then Cap. 2 about three hours ago and Cap. 3 about two. */
    private fun twoArrived(state: ReadingState = ReadingState.READING) {
        val now = Instant.now()
        read(now.minus(Duration.ofDays(3)), cap1)
        app.store.setReadingState(app.store.works().single().id, state)
        read(now.minus(Duration.ofMinutes(190)), cap1, cap2)
        read(now.minus(Duration.ofMinutes(130)), cap1, cap2, cap3)
    }

    private fun showHome(sync: KavitaSync = app.kavitaSync) {
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device", app.restores) }
        waitFor(app.getString(R.string.nav_home))
        compose.onNodeWithText(app.getString(R.string.nav_home)).performClick()
    }

    // 30 s, as in LibraryAppTest: the first screen of a run is slow on a 2-core CI runner.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private val heading get() = app.getString(R.string.home_arrived_title)

    private val line get() = app.getString(
        R.string.home_arrived_line,
        app.resources.getQuantityString(R.plurals.chapter_count, 2, 2),
        app.resources.getQuantityString(R.plurals.elapsed_hours, 2, 2),
    )

    private fun expand(title: String = "Sakamoto Days") =
        compose.onNode(hasContentDescription(app.getString(R.string.home_arrived_show, title))).performClick()

    @Test
    fun `dado dois capítulos que chegaram numa obra em Lendo, então Chegou mostra a obra recolhida, com quantos e há quanto tempo veio o mais novo`() {
        twoArrived()
        showHome()

        waitFor(heading)

        waitFor(line)
        assertEquals(1, count("Sakamoto Days"))
        assertEquals(0, count("Cap. 2"))
    }

    @Test
    fun `dado a obra recolhida em Chegou, quando toco a seta, então os capítulos novos aparecem do mais novo ao mais velho`() {
        twoArrived()
        showHome()
        waitFor(line)

        expand()

        waitFor("Cap. 3")
        assertTrue(top("Cap. 3") < top("Cap. 2"))
        assertEquals(0, count("Cap. 1"))
    }

    @Test
    fun `dado os capítulos abertos em Chegou, quando marco um como lido, então ele sai, e Desfazer o traz de volta`() {
        twoArrived()
        showHome()
        waitFor(line)
        expand()
        waitFor("Cap. 3")

        compose.onNode(hasContentDescription(app.getString(R.string.home_mark_read, "Cap. 3"))).performClick()

        waitUntilGone("Cap. 3")
        assertEquals(1, count("Cap. 2"))
        compose.onNode(hasText(app.getString(R.string.undo)) and hasClickAction()).performClick()
        waitFor("Cap. 3")
    }

    @Test
    fun `dado um capítulo marcado como lido em Chegou, quando vou à Biblioteca, então a linha da obra conta um novo a menos`() {
        twoArrived()
        showHome()
        waitFor(line)
        expand()
        waitFor("Cap. 3")
        compose.onNode(hasContentDescription(app.getString(R.string.home_mark_read, "Cap. 3"))).performClick()
        waitUntilGone("Cap. 3")

        compose.goToLibrary(app)

        val oneNew = app.resources.getQuantityString(R.plurals.new_count, 1, 1)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(oneNew, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `dado uma obra em Chegou, quando toco no título, então abre a Obra, e ao voltar ela saiu de Chegou`() {
        twoArrived()
        showHome()
        waitFor(line)

        compose.onNodeWithText("Sakamoto Days").performClick()
        waitFor(app.getString(R.string.back))
        pressBack()

        // Home is read again off the main thread, which waitForIdle does not wait for.
        waitFor(app.getString(R.string.nav_home))
        waitUntilGone(heading)
    }

    @Test
    fun `dado os capítulos abertos em Chegou, quando toco um capítulo e volto do leitor, então volto ao Início`() {
        twoArrived()
        showHome()
        waitFor(line)
        expand()
        waitFor("Cap. 3")

        compose.onNode(hasText("Cap. 3") and hasClickAction()).performClick()
        compose.waitForIdle()
        pressBack()

        waitFor(heading)
        assertEquals(0, count(app.getString(R.string.back)))
    }

    @Test
    fun `dado os capítulos abertos em Chegou, quando toco um capítulo e volto do leitor, então eles continuam abertos`() {
        twoArrived()
        showHome()
        waitFor(line)
        expand()
        waitFor("Cap. 3")

        compose.onNode(hasText("Cap. 3") and hasClickAction()).performClick()
        compose.waitForIdle()
        pressBack()

        waitFor(heading)
        waitFor("Cap. 2")
    }

    @Test
    fun `dado capítulos que chegaram numa obra em pausa, então Chegou não aparece`() {
        twoArrived(state = ReadingState.ON_HOLD)
        showHome()
        compose.waitForIdle()

        assertEquals(0, count(heading))
    }

    private val client = object : KavitaClient() {
        // Reading the libraries fails, so the import on opening leaves the works of the test alone.
        override fun libraries(address: String, key: String): List<RemoteLibrary> = throw IOException("No network in this test")

        override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) {
            target.writeText("pages of $chapterId")
        }
    }

    @Test
    fun `dado um capítulo do Kavita em Chegou, quando toco Baixar, então ele fica no aparelho e diz Baixado`() {
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "home-test"), client, downloadFolder = File(app.filesDir, "home-test"))
        sync.connect("https://a.example", "key-of-a-1234")
        val server = checkNotNull(app.store.kavitaServer()).id
        fun chapter(number: Int) = ImportedChapter("9$number", "Cap. $number", UnitKind.CHAPTER, number.toDouble(), ChapterFormat.ARCHIVE, "20|100", 20, "Cap. $number.cbz", 7)
        val now = Instant.now()
        app.store.importKavita(server, listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, listOf(chapter(1)))), at = now.minus(Duration.ofDays(3)))
        app.store.setReadingState(app.store.works().single { it.title == "Gantz" }.id, ReadingState.READING)
        app.store.importKavita(server, listOf(ImportedWork("12", "Gantz", WorkType.COMICS, null, 2, listOf(chapter(1), chapter(2)))), at = now.minus(Duration.ofMinutes(130)))
        showHome(sync)
        waitFor("Gantz")
        expand("Gantz")
        waitFor("Cap. 2")

        compose.onNode(hasContentDescription(app.getString(R.string.home_download, "Cap. 2"))).performClick()

        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(app.getString(R.string.chapter_downloaded))).fetchSemanticsNodes().isNotEmpty() }
    }
}
