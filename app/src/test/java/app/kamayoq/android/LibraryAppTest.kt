package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteProgress
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.data.BackupResult
import app.kamayoq.data.kavita.KavitaClient
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun showLibrary(titles: List<String>): FolderInMemory {
        val folder = FolderInMemory(app, titles)
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
        return folder
    }

    private fun showWithoutFolder() {
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
    }

    private fun kavitaWork(title: String, format: ChapterFormat = ChapterFormat.ARCHIVE) {
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, format, "3|100", 3, "Cap. 1.cbz", 7)
        app.store.importKavita("server-1", listOf(ImportedWork("12", title, WorkType.COMICS, null, 2, listOf(chapter))))
    }

    // 30 s: the first screen of a run once took more than 10 s on a 2-core CI runner (the backup
    // message test, 03/11/2026); the check itself is the same.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `dado a Biblioteca rolada até uma obra, quando abre a obra e volta, então a lista continua nessa obra`() {
        showLibrary((1..60).map { "Obra %02d".format(it) })
        waitFor("Obra 01")

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(45)
        compose.onNodeWithText("Obra 46").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText(app.getString(R.string.back)).performClick()

        // Back keeps the position.
        waitFor("Obra 46")
    }

    @Test
    fun `dado uma obra do Kavita e nenhuma pasta escolhida, quando abro a Biblioteca, então vejo a obra com o selo Kavita`() {
        kavitaWork("Obra do Kavita")

        showWithoutFolder()

        waitFor("Obra do Kavita")
        compose.onNodeWithText(" · Kavita", substring = true).assertExists()
    }

    @Test
    fun `dado um livro EPUB do Kavita, quando toco no capítulo, então o app avisa que ainda não abre`() {
        kavitaWork("Livro do Kavita", ChapterFormat.EPUB)
        showWithoutFolder()
        waitFor("Livro do Kavita")

        compose.onNodeWithText("Livro do Kavita").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()

        waitFor(app.getString(R.string.reader_epub_not_yet))
    }

    @Test
    fun `dado um CBR do Kavita, quando toco no capítulo, então o app avisa que o formato ainda não abre`() {
        kavitaWork("Obra CBR", ChapterFormat.OTHER_ARCHIVE)
        showWithoutFolder()
        waitFor("Obra CBR")

        compose.onNodeWithText("Obra CBR").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()

        waitFor(app.getString(R.string.reader_format_not_yet))
    }

    @Test
    fun `dado um capítulo do Kavita sem servidor alcançável, quando toco nele, então o app diz que não conseguiu baixar`() {
        kavitaWork("Obra sem rede")
        showWithoutFolder()
        waitFor("Obra sem rede")

        compose.onNodeWithText("Obra sem rede").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()

        waitFor(app.getString(R.string.reader_download_failed))
    }

    @Test
    fun `dado uma posição não escolhida guardada, quando abro a obra e toco em Usar, então ela vale e sai da Obra`() {
        kavitaWork("Obra em conflito")
        val work = app.store.works().single { it.title == "Obra em conflito" }
        val chapter = app.store.chapters(work.id).single()
        app.store.keepLocal(chapter.id, page = 2, pageCount = 3, serverAt = "x", at = Instant.EPOCH)
        showWithoutFolder()
        waitFor("Obra em conflito")

        compose.onNodeWithText("Obra em conflito").performClick()
        waitFor(app.getString(R.string.other_position_use))
        compose.onNodeWithText(app.getString(R.string.other_position_use)).performClick()

        compose.waitUntil(10_000) { app.store.progress(chapter.id).position?.page == 2 }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(app.getString(R.string.other_position_use))).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `dado leitura aqui num capítulo e no Kavita em outro, quando abro a Obra, então as duas opções aparecem sem Continuar`() {
        val chapters = listOf(1, 2).map { ImportedChapter("9$it", "Cap. $it", UnitKind.CHAPTER, it.toDouble(), ChapterFormat.ARCHIVE, "3|100", 3, "Cap. $it.cbz", 7) }
        app.store.importKavita("server-1", listOf(ImportedWork("12", "Obra cruzada", WorkType.COMICS, null, 2, chapters)))
        val work = app.store.works().single { it.title == "Obra cruzada" }
        val (first, second) = app.store.chapters(work.id)
        app.store.saveProgress(first.id, ChapterProgress(null, StripPosition(1, 3, Instant.EPOCH, "phone-a"), 0))
        val before = app.store.resumeOf(first.id)
        app.store.adoptRemote(second.id, 2, 3, "x", Instant.EPOCH, "PREVIOUS", moveResume = false)
        app.store.settleResume(second.id, localMoved = true, before = before)
        showWithoutFolder()
        waitFor("Obra cruzada")

        compose.onNodeWithText("Obra cruzada").performClick()

        waitFor(app.getString(R.string.conflict_title))
        compose.onNodeWithText(app.getString(R.string.continue_reading, "Cap. 1")).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.conflict_here, "Cap. 1")).assertExists()
        compose.onNodeWithText(app.getString(R.string.conflict_kavita, "Cap. 2")).performClick()

        compose.waitUntil(10_000) { app.store.resumeChapter(work.id)?.id == second.id }
    }

    @Test
    fun `dado um conflito, quando toco no capítulo, então vejo as duas posições, o leitor espera e a escolha vale`() {
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        val client = object : KavitaClient() {
            override fun progress(address: String, key: String, chapterId: Int) = RemoteProgress(2, "2026-11-02T10:00:00")
            override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) =
                throw IOException("No network")
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "conflict"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        val server = checkNotNull(app.store.kavitaServer()).id
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "3|100", 3, "Cap. 1.cbz", 7)
        app.store.importKavita(server, listOf(ImportedWork("12", "Obra dividida", WorkType.COMICS, null, 2, listOf(chapter))))
        val stored = app.store.chapters(app.store.works().single().id).single()
        app.store.saveProgress(stored.id, ChapterProgress(null, StripPosition(1, 3, Instant.EPOCH, "phone-a"), 0))
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device") }
        waitFor("Obra dividida")

        compose.onNodeWithText("Obra dividida").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()
        waitFor(app.getString(R.string.conflict_title))
        compose.onNodeWithText(app.getString(R.string.conflict_kavita, app.getString(R.string.position_page, 3, 3))).performClick()

        compose.waitUntil(10_000) { app.store.progress(stored.id).position?.page == 2 }
        waitFor(app.getString(R.string.reader_download_failed))
    }

    @Test
    fun `dado a Biblioteca, quando toco em Conectar ao Kavita, então a tela de conexão abre e Voltar retorna`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        compose.onNodeWithText(app.getString(R.string.kavita_connect)).performClick()
        waitFor(app.getString(R.string.kavita_address))
        compose.onNodeWithText(app.getString(R.string.back)).performClick()

        waitFor("Obra")
    }

    @Test
    fun `dado o resultado de um backup, quando ele aparece, então o TalkBack anuncia a mensagem`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        compose.runOnUiThread { app.backups.start { "Kamayoq" to BackupResult.Mismatch } }

        val message = app.getString(R.string.backup_mismatch)
        waitFor(message)
        compose.onNodeWithText(message)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `dado obras novas na pasta com o app em segundo plano, quando ele volta, então a pasta é lida de novo`() {
        val folder = showLibrary(listOf("Obra antiga"))
        waitFor("Obra antiga")

        // Back only sends the app to the background since Android 12.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        folder.titles = listOf("Obra antiga", "Obra nova")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitFor("Obra nova")
    }

    @Test
    fun `dado obras que saíram da lista e uma leitura da pasta que falha, então a lista mostra só o que continua no banco`() {
        val folder = showLibrary(listOf("Obra"))
        waitFor("Obra")

        // What choosing another folder does before reading it.
        app.store.importLocal(folder.tree.toString(), LocalLibrary(emptyList(), emptyList()), emptyMap())
        folder.failing = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitFor(app.getString(R.string.reading_folder_failed))
        compose.onNodeWithText("Obra").assertDoesNotExist()
    }

    @Test
    fun `dado uma pasta só de leitura sem Library, então a tela diz que não há obras, sem dizer que a leitura falhou`() {
        val folder = FolderInMemory(app, emptyList()).apply { readOnlyWithoutLibrary = true }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device") }

        // Rule E5, reading never depends on writing.
        waitFor(app.getString(R.string.no_works_found))
        compose.onNodeWithText(app.getString(R.string.reading_folder_failed)).assertDoesNotExist()
    }

    @Test
    fun `dado a lista de arquivos deixados de fora, quando a tela é recriada e a leitura falha, então a lista continua`() {
        val folder = FolderInMemory(app, listOf("Obra")).apply { looseFiles = listOf("notas.txt") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
        waitFor(app.getString(R.string.see_ignored_files))
        compose.onNodeWithText(app.getString(R.string.see_ignored_files)).performClick()
        waitFor("Comics/notas.txt")

        folder.failing = true
        restoration.emulateSavedInstanceStateRestore()

        waitFor("Comics/notas.txt")
    }
}
