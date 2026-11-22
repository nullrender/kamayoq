package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.data.kavita.KavitaClient
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 3 of the v1 interface: "Download" on the Work screen, with the progress there (decision 5).
@RunWith(RobolectricTestRunner::class)
class ChapterDownloadTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    /** Chapters whose download fails, by Kavita's chapter id. */
    private val failing = mutableSetOf<String>()

    /** Chapters whose download breaks with an error that is not the network's. */
    private val breaking = mutableSetOf<String>()

    /** Held while set: each download waits for it, as over a slow network. */
    @Volatile
    private var slow: CountDownLatch? = null

    private val client = object : KavitaClient() {
        // Reading the libraries fails, so the import on opening leaves the works of the test alone.
        override fun libraries(address: String, key: String): List<RemoteLibrary> = throw IOException("No network in this test")

        override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) {
            slow?.await(10, TimeUnit.SECONDS)
            if (chapterId in failing) throw IOException("Cut")
            if (chapterId in breaking) throw IllegalStateException("Not a network failure")
            target.writeText("pages of $chapterId")
        }
    }

    private lateinit var sync: KavitaSync

    /** [beforeShowing] runs once the work is in the store and before the screen first reads it. */
    private fun open(
        title: String,
        kavita: Boolean = true,
        restoration: StateRestorationTester? = null,
        formats: List<ChapterFormat> = List(3) { ChapterFormat.ARCHIVE },
        beforeShowing: () -> Unit = {},
    ) {
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        sync = KavitaSync(app.store, access, File(app.cacheDir, "download-test"), client, downloadFolder = File(app.filesDir, "download-test"))
        sync.connect("https://a.example", "key-of-a-1234")
        if (kavita) {
            val chapters = formats.mapIndexed { index, format ->
                val number = index + 1
                ImportedChapter("9$number", "Cap. $number", UnitKind.CHAPTER, number.toDouble(), format, "20|100", 20, "Cap. $number.cbz", 7)
            }
            app.store.importKavita(checkNotNull(app.store.kavitaServer()).id, listOf(ImportedWork("12", title, WorkType.COMICS, null, 2, chapters)))
        }
        beforeShowing()
        val folder = FolderInMemory(app, if (kavita) emptyList() else listOf(title))
        val screen = @androidx.compose.runtime.Composable {
            LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device", app.restores, app.covers)
        }
        if (restoration != null) restoration.setContent(screen) else compose.setContent(screen)
        compose.goToLibrary(app)
        waitFor(title)
        compose.onNode(hasText(title)).performClick()
        waitFor("Cap. 1")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun chapters() = app.store.chapters(app.store.works().single().id)

    private fun downloaded() = chapters().map { sync.isDownloaded(it) }

    private fun downloadedOnScreen() = compose.onAllNodes(hasText(app.getString(R.string.chapter_downloaded))).fetchSemanticsNodes().size

    private fun openSheet() {
        compose.onNode(hasText(app.getString(R.string.download)) and !hasAnyAncestor(hasTestTag(SELECTION_BAR))).performClick()
        waitFor(app.getString(R.string.download_sheet_title))
    }

    @Test
    fun `dado a folha Baixar aberta, então a alça de arrastar tem um alvo de pelo menos 48 dp de largura`() {
        open("Gantz")

        openSheet()

        // Material's own description of the handle; WorkStateTest checks the same on the state sheet.
        compose.onNode(hasContentDescription("Drag handle")).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `dado uma obra do Kavita, quando escolho Todos na folha Baixar, então os três ficam no aparelho e cada capítulo diz Baixado`() {
        open("Gantz")

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.download_started, 3, 3))
        compose.waitUntil(10_000) { downloaded() == listOf(true, true, true) }
        compose.waitUntil(10_000) { downloadedOnScreen() == 3 }
    }

    @Test
    fun `dado o primeiro capítulo lido, quando escolho Não lidos, então só os dois outros são baixados`() {
        open("Gantz") { app.store.markRead(app.store.works().single().id, listOf(chapters().first().id), Instant.now()) }

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_unread))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.download_started, 2, 2))
        compose.waitUntil(10_000) { downloaded() == listOf(false, true, true) }
    }

    @Test
    fun `dado tudo já no aparelho, quando peço Todos de novo, então o aviso diz que nada foi baixado`() {
        open("Gantz")
        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()
        compose.waitUntil(10_000) { downloadedOnScreen() == 3 }

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()

        waitFor(app.getString(R.string.download_none_new))
    }

    @Test
    fun `dado três baixados, quando apago pela folha e confirmo, então saem do aparelho e as marcas de lido ficam`() {
        open("Gantz")
        val workId = app.store.works().single().id
        app.store.markRead(workId, listOf(chapters().first().id), Instant.now())
        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()
        compose.waitUntil(10_000) { downloadedOnScreen() == 3 }

        openSheet()
        compose.onNode(hasText(app.resources.getQuantityString(R.plurals.download_delete, 3, 3))).performClick()
        waitFor(app.resources.getQuantityString(R.plurals.delete_downloads_title, 3, 3))
        compose.onNode(hasText(app.getString(R.string.delete_downloads_confirm, 3))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.downloads_deleted, 3, 3))
        assertEquals(listOf(false, false, false), downloaded())
        assertTrue(app.store.progress(chapters().first().id).readMark != null)
    }

    @Test
    fun `dado um capítulo que falha, quando baixo todos, então a Obra diz que um não baixou e os outros ficam`() {
        failing += "92"
        open("Gantz")

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.download_failed, 1, 1))
        assertEquals(listOf(true, false, true), downloaded())
    }

    @Test
    fun `dado um capítulo selecionado, quando toco em Baixar na seleção, então só ele é baixado`() {
        open("Gantz")

        compose.onNode(hasText("Cap. 2")).performTouchInput { longClick() }
        compose.onNode(hasText(app.getString(R.string.download)) and hasAnyAncestor(hasTestTag(SELECTION_BAR))).performClick()

        compose.waitUntil(10_000) { downloaded() == listOf(false, true, false) }
    }

    @Test
    fun `dado uma obra da pasta do aparelho, então a Obra não oferece Baixar`() {
        open("Sakamoto Days", kavita = false)

        assertEquals(0, compose.onAllNodes(hasText(app.getString(R.string.download))).fetchSemanticsNodes().size)
    }

    @Test
    fun `dado um capítulo que quebra com um erro que não é de rede, quando baixo todos, então a Obra diz que um não baixou e os outros vêm`() {
        breaking += "92"
        open("Gantz")

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.download_failed, 1, 1))
        compose.waitUntil(10_000) { downloaded() == listOf(true, false, true) }
    }

    @Test
    fun `dado um download em andamento, quando peço Todos de novo, então o total continua o número de capítulos`() {
        val latch = CountDownLatch(1)
        slow = latch
        open("Gantz")
        val workId = app.store.works().single().id

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()
        waitFor(app.resources.getQuantityString(R.plurals.download_started, 3, 3))
        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()
        compose.waitForIdle()
        val total = sync.downloads.value[workId]?.total
        latch.countDown()
        slow = null

        assertEquals(3, total)
        compose.waitUntil(10_000) { downloaded() == listOf(true, true, true) }
    }

    @Test
    fun `dado a confirmação de apagar baixados aberta, quando a tela é recriada, então ela continua aberta`() {
        val restoration = StateRestorationTester(compose)
        open("Gantz", restoration = restoration)
        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()
        compose.waitUntil(10_000) { downloadedOnScreen() == 3 }
        openSheet()
        compose.onNode(hasText(app.resources.getQuantityString(R.plurals.download_delete, 3, 3))).performClick()
        waitFor(app.resources.getQuantityString(R.plurals.delete_downloads_title, 3, 3))

        restoration.emulateSavedInstanceStateRestore()

        waitFor(app.resources.getQuantityString(R.plurals.delete_downloads_title, 3, 3))
    }

    @Test
    fun `dado um capítulo em PDF entre dois em CBZ, quando baixo todos, então só os dois que o leitor abre são baixados`() {
        open("Gantz", formats = listOf(ChapterFormat.ARCHIVE, ChapterFormat.PDF, ChapterFormat.ARCHIVE))

        openSheet()
        compose.onNode(hasText(app.getString(R.string.download_all))).performClick()

        waitFor(app.resources.getQuantityString(R.plurals.download_started, 2, 2))
        compose.waitUntil(10_000) { downloaded() == listOf(true, false, true) }
    }
}
