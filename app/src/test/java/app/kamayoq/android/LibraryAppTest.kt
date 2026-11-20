package app.kamayoq.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.backup.Backup
import app.kamayoq.core.backup.BackupChapter
import app.kamayoq.core.backup.BackupFormat
import app.kamayoq.core.backup.BackupServer
import app.kamayoq.core.backup.BackupWork
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteProgress
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.Work
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.epubfixtures.EpubFixtures
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.progress.strip
import app.kamayoq.core.reader.EpubLimits
import app.kamayoq.data.BackupResult
import app.kamayoq.data.kavita.KavitaChapterFile
import app.kamayoq.data.kavita.KavitaClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LibraryAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun showLibrary(titles: List<String>): FolderInMemory {
        val folder = FolderInMemory(app, titles)
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        return folder
    }

    private fun showWithoutFolder() {
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
    }

    private fun kavitaWork(title: String, format: ChapterFormat = ChapterFormat.ARCHIVE) {
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, format, "3|100", 3, "Cap. 1.cbz", 7)
        app.store.importKavita("server-1", listOf(ImportedWork("12", title, WorkType.COMICS, null, 2, listOf(chapter))))
    }

    // 30 s: the first screen of a run once took more than 10 s on a 2-core CI runner (the backup
    // message test, 03/11/2026); the check itself is the same.
    private fun waitFor(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

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

    // Unclipped: a node below the fold of the test's small screen still has its real place.
    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** Taps a node, scrolling to it first when it sits in a scrolling screen, as a person would. */
    private fun tap(text: String) {
        val node = hasText(text)
        if (compose.onAllNodes(node and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(node).performScrollTo()
        }
        compose.onNode(node).performClick()
    }

    @Test
    fun `dado quadrinhos e livros na pasta, então cada obra aparece sob a seção do seu tipo e recolher Quadrinhos esconde só as obras dele`() {
        val folder = FolderInMemory(app, listOf("Sakamoto Days")).apply { books = listOf("Dom Casmurro") }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Sakamoto Days")
        waitFor("Dom Casmurro")
        val comics = app.getString(R.string.section_comics)
        val books = app.getString(R.string.section_books)

        assertTrue(top(comics) < top("Sakamoto Days"))
        assertTrue(top("Sakamoto Days") < top(books))
        assertTrue(top(books) < top("Dom Casmurro"))
        compose.onNodeWithText(comics).performClick()

        waitUntilGone("Sakamoto Days")
        compose.onNodeWithText("Dom Casmurro").assertExists()
        compose.onNodeWithText(comics).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.getString(R.string.section_collapsed)))
    }

    @Test
    fun `dado uma seção recolhida, quando a tela é recriada, então ela continua recolhida`() {
        val folder = FolderInMemory(app, listOf("Sakamoto Days")).apply { books = listOf("Dom Casmurro") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Sakamoto Days")
        compose.onNodeWithText(app.getString(R.string.section_comics)).performClick()
        waitUntilGone("Sakamoto Days")

        restoration.emulateSavedInstanceStateRestore()

        waitFor("Dom Casmurro")
        compose.onNodeWithText("Sakamoto Days").assertDoesNotExist()
    }

    private fun search(text: String) {
        compose.onNodeWithContentDescription(app.getString(R.string.search_open)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `dado obras da pasta e do Kavita, quando procuro pela lupa, então só as que respondem aparecem, das duas origens`() {
        kavitaWork("Escravo das Sombras")
        val folder = FolderInMemory(app, listOf("Sakamoto Days", "Sombras de Ébano")).apply { books = listOf("Dom Casmurro") }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Sakamoto Days")
        waitFor("Escravo das Sombras")

        search("sombras")

        waitUntilGone("Sakamoto Days")
        compose.onNodeWithText("Escravo das Sombras").assertExists()
        compose.onNodeWithText("Sombras de Ébano").assertExists()
        compose.onNodeWithText("Dom Casmurro").assertDoesNotExist()
    }

    @Test
    fun `dado uma busca aberta, quando aperto Voltar, então a busca fecha e todas as obras voltam, sem sair da Biblioteca`() {
        val folder = FolderInMemory(app, listOf("Sakamoto Days")).apply { books = listOf("Dom Casmurro") }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Sakamoto Days")
        search("casmurro")
        waitUntilGone("Sakamoto Days")

        pressBack()

        waitFor("Sakamoto Days")
        compose.onNodeWithText("Dom Casmurro").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `dado uma busca que nada responde, então a tela diz que nenhuma obra tem esse nome`() {
        showLibrary(listOf("Sakamoto Days"))
        waitFor("Sakamoto Days")

        search("kaiju")

        waitFor(app.getString(R.string.search_none))
        compose.onNodeWithText("Sakamoto Days").assertDoesNotExist()
    }

    // The bar's items, not the screen title of the same name.
    private fun tab(label: Int) = compose.onNode(hasText(app.getString(label)) and isSelectable())

    private fun openTab(label: Int) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(app.getString(label)) and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        tab(label).performClick()
    }

    @Test
    fun `dado a Biblioteca, então a barra mostra Início, Biblioteca e Ajustes, com a Biblioteca escolhida`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        openTab(R.string.nav_library)

        tab(R.string.nav_home).assertIsNotSelected()
        tab(R.string.nav_library).assertIsSelected()
        tab(R.string.nav_settings).assertIsNotSelected()
        assertTrue(top(app.getString(R.string.nav_home)) > top("Obra"))
    }

    @Test
    fun `dado a Biblioteca, quando toco em Ajustes, então vejo a pasta, o Kavita e o backup e Voltar volta à Biblioteca`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        openTab(R.string.nav_settings)

        waitFor(app.getString(R.string.kavita_connect))
        compose.onNodeWithText(app.getString(R.string.back_up)).assertExists()
        compose.onNodeWithText(app.getString(R.string.change_folder)).assertExists()
        compose.onNodeWithText("Obra").assertDoesNotExist()
        pressBack()
        waitFor("Obra")
        tab(R.string.nav_library).assertIsSelected()
    }

    @Test
    fun `dado o Início com obras na biblioteca, então ele diz que o conteúdo dele vem depois e aponta a Biblioteca`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        openTab(R.string.nav_home)

        waitFor(app.getString(R.string.home_later))
        tab(R.string.nav_home).assertIsSelected()
    }

    @Test
    fun `dado uma obra aberta da Biblioteca, então a barra não aparece, e volta com a lista`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")
        openTab(R.string.nav_library)

        compose.onNodeWithText("Obra").performClick()
        waitFor("Cap. 1")

        compose.onAllNodes(hasText(app.getString(R.string.nav_settings)) and isSelectable()).assertCountEquals(0)
        pressBack()
        waitFor("Obra")
        tab(R.string.nav_settings).assertExists()
    }

    // The first opening (board PrimeiraAbertura), in the order decided on 24/10/2026.

    private fun assertFirstOpening() {
        waitFor(app.getString(R.string.empty_title))
        val files = top(app.getString(R.string.source_files))
        val kavita = top(app.getString(R.string.kavita_connect))
        val restore = top(app.getString(R.string.restore_backup))
        assertTrue("Arquivos antes do Kavita", files < kavita)
        assertTrue("Kavita antes de Restaurar", kavita < restore)
    }

    @Test
    fun `dado o app vazio e sem pasta, então a Biblioteca diz que está vazia e mostra Arquivos, Kavita e Restaurar nessa ordem`() {
        showWithoutFolder()

        assertFirstOpening()
        compose.onNodeWithText(app.getString(R.string.no_folder)).assertDoesNotExist()
    }

    @Test
    fun `dado o app vazio, quando vou ao Início, então ele mostra os mesmos três caminhos`() {
        showWithoutFolder()
        waitFor(app.getString(R.string.empty_title))

        openTab(R.string.nav_home)

        assertFirstOpening()
        compose.onNodeWithText(app.getString(R.string.home_later)).assertDoesNotExist()
    }

    @Test
    fun `dado o app vazio, quando toco em Arquivos deste aparelho, então o seletor de pasta do Android abre`() {
        showWithoutFolder()
        waitFor(app.getString(R.string.source_files))

        tap(app.getString(R.string.source_files))
        compose.waitForIdle()

        val started = checkNotNull(shadowOf(compose.activity).nextStartedActivityForResult) { "No picker was opened" }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
    }

    @Test
    fun `dado o app vazio, quando toco em Conectar ao Kavita, então a tela de conexão abre`() {
        showWithoutFolder()
        waitFor(app.getString(R.string.empty_title))

        tap(app.getString(R.string.kavita_connect))

        waitFor(app.getString(R.string.kavita_address))
    }

    @Test
    fun `dado uma obra do Kavita e nenhuma pasta escolhida, quando abro a Biblioteca, então vejo a obra com o selo Kavita`() {
        // A title without the word Kavita: only the badge can say where the work comes from.
        kavitaWork("Obra remota")

        showWithoutFolder()

        waitFor("Obra remota")
        compose.onNode(hasText("Obra remota") and hasText(app.getString(R.string.origin_kavita), substring = true)).assertExists()
    }

    // Milestone C1 of step 4: a book from Kavita goes the way of a local one, through the download.
    @Test
    fun `dado um livro EPUB do Kavita sem servidor alcançável, quando toco no capítulo, então o app diz que não conseguiu baixar`() {
        kavitaWork("Livro sem rede", ChapterFormat.EPUB)
        showWithoutFolder()
        waitFor("Livro sem rede")

        compose.onNodeWithText("Livro sem rede").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()

        waitFor(app.getString(R.string.reader_download_failed))
    }

    // The ceiling in the message proves the kept file went to the book's copy, not to the CBZ's.
    @Test
    fun `dado um livro EPUB do Kavita já guardado maior que o teto, quando toco no capítulo, então a cópia para e o app diz o teto`() {
        kavitaWork("Livro grande", ChapterFormat.EPUB)
        val kept = File(app.cacheDir, "kavita-large.epub").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        val opening = ChapterOpening(app, folder, app.store, { _, _ -> KavitaChapterFile(kept, outdated = false) }, EpubLimits(maxFileBytes = 1024 * 1024))
        compose.setContent { LibraryApp(app.store, folder, opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Livro grande")

        compose.onNodeWithText("Livro grande").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()

        waitFor(app.getString(R.string.reader_too_large, 1))
    }

    // The ceiling comes from the opening, so the message says the ceiling that stopped the copy.
    @Test
    fun `dado um livro EPUB maior que o teto, quando toco no volume, então a cópia para e o app diz o teto`() {
        val folder = FolderInMemory(app, emptyList()).apply { books = listOf("Os Miseráveis") }
        val twoMegabytes = ByteArrayInputStream(ByteArray(2 * 1024 * 1024))
        shadowOf(app.contentResolver).registerInputStream(
            DocumentsContract.buildDocumentUriUsingTree(folder.tree, folder.bookFileId("Os Miseráveis")),
            twoMegabytes,
        )
        val opening = ChapterOpening(app, folder, app.store, app.kavitaSync::chapterFile, EpubLimits(maxFileBytes = 1024 * 1024))
        compose.setContent { LibraryApp(app.store, folder, opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Os Miseráveis")

        compose.onNodeWithText("Os Miseráveis").performClick()
        waitFor("Vol. 1")
        compose.onNodeWithText("Vol. 1").performClick()

        waitFor(app.getString(R.string.reader_too_large, 1))
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

        compose.waitUntil(10_000) { app.store.progress(chapter.id).position?.strip()?.page == 2 }
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
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device", app.restores) }
        waitFor("Obra dividida")

        compose.onNodeWithText("Obra dividida").performClick()
        waitFor("Cap. 1")
        compose.onNodeWithText("Cap. 1").performClick()
        waitFor(app.getString(R.string.conflict_title))
        compose.onNodeWithText(app.getString(R.string.conflict_kavita, app.getString(R.string.position_page, 3, 3))).performClick()

        compose.waitUntil(10_000) { app.store.progress(stored.id).position?.strip()?.page == 2 }
        waitFor(app.getString(R.string.reader_download_failed))
    }

    // Milestone C2 of step 4: a book from Kavita asks before it opens, as a CBZ chapter does.
    @Test
    fun `dado um conflito num livro do Kavita, quando toco nele, então vejo as seções e Usar o Kavita traz o lugar com o marcador`() {
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        val client = object : KavitaClient() {
            override fun progress(address: String, key: String, chapterId: Int) = RemoteProgress(2, "2026-11-18T10:00:00", "//body/p[4]")
            override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) =
                throw IOException("No network")
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "book-conflict"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        val server = checkNotNull(app.store.kavitaServer()).id
        val chapter = ImportedChapter("93", "Vol. 1", UnitKind.VOLUME, null, ChapterFormat.EPUB, "3|100", 3, "Livro.epub", 7)
        app.store.importKavita(server, listOf(ImportedWork("14", "Livro dividido", WorkType.BOOKS, null, 2, listOf(chapter))))
        val stored = app.store.chapters(app.store.works().single().id).single()
        val here = TextPosition(1, 3, "epubcfi(/6/4!/4/2/1:0)", checkNotNull(BasisPoints.fromFraction(0.4)), Instant.EPOCH, "phone-a")
        app.store.saveProgress(stored.id, ChapterProgress(null, here, 0), marker = "//body/p[2]")
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device", app.restores) }
        waitFor("Livro dividido")

        compose.onNodeWithText("Livro dividido").performClick()
        waitFor("Vol. 1")
        compose.onNodeWithText("Vol. 1").performClick()
        waitFor(app.getString(R.string.conflict_title))
        compose.onNodeWithText(app.getString(R.string.conflict_here, app.getString(R.string.position_section, 2, 3))).assertExists()
        compose.onNodeWithText(app.getString(R.string.conflict_kavita, app.getString(R.string.position_section, 3, 3))).performClick()

        compose.waitUntil(10_000) { (app.store.progress(stored.id).position as? TextPosition)?.section == 2 }
        assertEquals("//body/p[4]", app.store.agreedMarker(stored.id))
        waitFor(app.getString(R.string.reader_download_failed))
    }

    @Test
    fun `dado os Ajustes, quando toco em Conectar ao Kavita, então a tela de conexão abre e Voltar retorna aos Ajustes`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")
        openTab(R.string.nav_settings)

        compose.onNodeWithText(app.getString(R.string.kavita_connect)).performClick()
        waitFor(app.getString(R.string.kavita_address))
        compose.onNodeWithText(app.getString(R.string.back)).performClick()

        waitFor(app.getString(R.string.kavita_connect))
        tab(R.string.nav_settings).assertIsSelected()
    }

    @Test
    fun `dado o resultado de um backup, quando ele aparece, então o TalkBack anuncia a mensagem`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")
        openTab(R.string.nav_settings)

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
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }

        // Rule E5, reading never depends on writing.
        waitFor(app.getString(R.string.no_works_found))
        compose.onNodeWithText(app.getString(R.string.reading_folder_failed)).assertDoesNotExist()
    }

    @Test
    fun `dado a lista de arquivos deixados de fora, quando a tela é recriada e a leitura falha, então a lista continua`() {
        val folder = FolderInMemory(app, listOf("Obra")).apply { looseFiles = listOf("notas.txt") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor(app.getString(R.string.see_ignored_files))
        compose.onNodeWithText(app.getString(R.string.see_ignored_files)).performClick()
        waitFor("Comics/notas.txt")

        folder.failing = true
        restoration.emulateSavedInstanceStateRestore()

        waitFor("Comics/notas.txt")
    }

    // Step 4, milestone B: a compiled novel is one file, shown as the chapters of its table of contents.

    private val compiledNovel = "Novels/Romance/Romance.epub"

    private fun importNovel(titles: List<String>, modified: Long = 1) {
        val units = titles.mapIndexed { index, title ->
            ReadingUnit(compiledNovel, title, UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = if (titles.size == 1) "" else "OEBPS/c$index.xhtml")
        }
        val work = Work("Novels/Romance", "Romance", null, WorkType.NOVELS, null, units)
        app.store.importLocal("content://test/tree/primary%3AKamayoq", LocalLibrary(listOf(work), emptyList()), mapOf(compiledNovel to FileStamp(10, modified)))
    }

    /**
     * Shows the Library on the folder that holds the novel stored by [importNovel], with the stamp
     * it was stored with: the reading of the folder finds the file unchanged and keeps what is stored.
     * Local works only show with a folder chosen.
     */
    private fun showNovelFolder(modified: Long = 1) {
        val folder = FolderInMemory(app, emptyList()).apply {
            novels = listOf("Romance")
            novelsModified = modified
        }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
    }

    @Test
    fun `dado um romance compilado na pasta, quando a Biblioteca lê a pasta, então a obra mostra os capítulos do sumário do arquivo`() {
        val folder = FolderInMemory(app, emptyList()).apply { novels = listOf("Novela em volumes") }
        shadowOf(app.contentResolver).registerInputStream(
            DocumentsContract.buildDocumentUriUsingTree(folder.tree, folder.novelFileId("Novela em volumes")),
            ByteArrayInputStream(EpubFixtures.novel()),
        )
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device", app.restores) }
        waitFor("Novela em volumes")

        compose.onNodeWithText("Novela em volumes").performClick()

        waitFor("Capítulo 1")
        for (chapter in 2..5) compose.onNodeWithText("Capítulo $chapter").assertExists()
        compose.onNodeWithText(app.getString(R.string.file_undivided)).assertDoesNotExist()
    }

    @Test
    fun `dado um romance cujo arquivo mudou de edição com capítulos lidos, quando abro a obra e aplico, então o capítulo de mesmo título fica lido e a pergunta some`() {
        importNovel(listOf("Capítulo 1", "Capítulo 2"))
        val read = ChapterProgress(ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END), null, 1)
        app.store.saveProgress(app.store.chapters(app.store.works().single().id).first().id, read)
        importNovel(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), modified = 2)
        showNovelFolder(modified = 2)
        waitFor("Romance")
        compose.onNodeWithText("Romance").performClick()
        val question = app.getString(R.string.edition_changed, "Capítulo 1")
        waitFor(question)
        compose.onNodeWithText(app.getString(R.string.chapter_read)).assertDoesNotExist()

        compose.onNodeWithText(app.getString(R.string.edition_apply)).performClick()

        // The mark goes by title: on the row of "Capítulo 1", not on the preface now in its old position.
        val readLabel = app.getString(R.string.chapter_read)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Capítulo 1") and hasText(readLabel)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Prefácio") and hasText(readLabel)).assertDoesNotExist()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `dado a pergunta do arquivo que mudou, quando escolho não aplicar, então a pergunta some e nada fica lido`() {
        importNovel(listOf("Capítulo 1", "Capítulo 2"))
        val read = ChapterProgress(ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END), null, 1)
        app.store.saveProgress(app.store.chapters(app.store.works().single().id).first().id, read)
        importNovel(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), modified = 2)
        showNovelFolder(modified = 2)
        waitFor("Romance")
        compose.onNodeWithText("Romance").performClick()
        val question = app.getString(R.string.edition_changed, "Capítulo 1")
        waitFor(question)

        compose.onNodeWithText(app.getString(R.string.edition_dismiss)).performClick()
        // Decided on 18/11/2026: there is no way back, so it asks first.
        waitFor(app.getString(R.string.edition_dismiss_text))
        compose.onNodeWithText(app.getString(R.string.edition_dismiss_confirm)).performClick()

        compose.waitUntil(30_000) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(app.getString(R.string.chapter_read)).assertDoesNotExist()
    }

    // Decided on 18/11/2026: "Don't apply" has no undo and no way to bring the question back, so it
    // asks first (AGENTS.md, a destructive action).
    @Test
    fun `dado a pergunta do arquivo que mudou, quando toco em não aplicar e mantenho a pergunta na confirmação, então ela continua e nada é descartado`() {
        importNovel(listOf("Capítulo 1", "Capítulo 2"))
        val read = ChapterProgress(ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END), null, 1)
        app.store.saveProgress(app.store.chapters(app.store.works().single().id).first().id, read)
        importNovel(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), modified = 2)
        showNovelFolder(modified = 2)
        waitFor("Romance")
        compose.onNodeWithText("Romance").performClick()
        val question = app.getString(R.string.edition_changed, "Capítulo 1")
        waitFor(question)

        compose.onNodeWithText(app.getString(R.string.edition_dismiss)).performClick()
        waitFor(app.getString(R.string.edition_dismiss_text))
        compose.onNodeWithText(app.getString(R.string.edition_dismiss_keep)).performClick()

        compose.waitUntil(30_000) { compose.onAllNodes(hasText(app.getString(R.string.edition_dismiss_text))).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(question).assertExists()
        assertEquals(1, app.store.editionOffers(app.store.works().single().id).size)
    }

    @Test
    fun `dado um romance compilado cujo arquivo não traz sumário, quando abro a obra, então ela avisa que o arquivo não traz a divisão`() {
        importNovel(listOf("Romance"))
        showNovelFolder()
        waitFor("Romance")

        compose.onNodeWithText("Romance").performClick()

        waitFor(app.getString(R.string.file_undivided))
    }

    // Restoring a backup on an empty app (docs/decisions/backup-restore-2026-11-18.md).

    private val stoppedAt = Instant.parse("2026-11-18T12:00:00Z")

    /** A backup with a local work read to the end and a Kavita work halfway, on the server it came from. */
    private val backup = Backup(
        appVersion = "r550",
        exportedAt = Instant.parse("2026-11-18T22:00:00Z"),
        servers = listOf(BackupServer("server-1", "http://kavita.example:5000", "0.9.1.4", 1, listOf(2))),
        works = listOf(
            BackupWork(
                id = "work-1", versionId = "version-1", origin = "local", path = "Comics/Sakamoto Days", title = "Sakamoto Days",
                alternativeTitle = null, type = WorkType.COMICS, subtype = null, present = true,
                chapters = listOf(
                    BackupChapter(
                        id = "chapter-1", replacedBy = null, path = "Comics/Sakamoto Days/Cap. 1.cbz", name = "Cap. 1",
                        kind = UnitKind.CHAPTER, number = 1.0, format = ChapterFormat.ARCHIVE, contentHash = "ab12", present = true,
                        resume = true,
                        progress = ChapterProgress(ReadMark(stoppedAt, ReadMarkSource.READ_TO_END), StripPosition(10, 10, stoppedAt, "phone-a"), 1),
                    ),
                ),
            ),
            BackupWork(
                id = "work-12", versionId = "version-12", origin = "kavita", path = "12", title = "Kaiju No. 8",
                alternativeTitle = null, type = WorkType.COMICS, subtype = null, present = true,
                chapters = listOf(
                    BackupChapter(
                        id = "chapter-91", replacedBy = null, path = "91", name = "Cap. 91", kind = UnitKind.CHAPTER, number = 91.0,
                        format = ChapterFormat.ARCHIVE, contentHash = null, present = true, resume = true,
                        progress = ChapterProgress(null, StripPosition(17, 42, stoppedAt, "phone-a"), 0),
                        pageCount = 42, remoteFile = "Cap. 91.cbz", remoteVolumeId = 7,
                    ),
                ),
                source = "server-1",
                remoteLibraryId = 2,
            ),
        ),
    )

    /** Taps Restore backup and answers Android's file picker with a file holding [text]. */
    private fun pickBackup(text: String) {
        waitFor(app.getString(R.string.restore_backup))
        tap(app.getString(R.string.restore_backup))
        compose.waitForIdle()
        val started = checkNotNull(shadowOf(compose.activity).nextStartedActivityForResult) { "No picker was opened" }
        val file = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AKamayoq%2FBackups%2Fkamayoq_2026-11-18_19-00.json")
        shadowOf(app.contentResolver).registerInputStream(file, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        shadowOf(compose.activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(file))
    }

    @Test
    fun `dado o app vazio, quando toco em Restaurar backup, então o seletor de arquivos do Android abre para qualquer tipo de arquivo`() {
        showWithoutFolder()
        waitFor(app.getString(R.string.restore_backup))

        tap(app.getString(R.string.restore_backup))
        compose.waitForIdle()

        val started = checkNotNull(shadowOf(compose.activity).nextStartedActivityForResult) { "No picker was opened" }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        // A backup copied by cable or a cloud drive may come with another type than JSON (choice 10).
        assertEquals(listOf("*/*"), started.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test
    fun `dado um backup escolhido sem pasta, quando confirmo, então a obra do Kavita aparece e o TalkBack anuncia que falta escolher a pasta`() {
        showWithoutFolder()

        pickBackup(BackupFormat.write(backup))
        waitFor(app.getString(R.string.restore_confirm_title))
        compose.onNodeWithText(app.getString(R.string.restore_confirm)).performClick()

        val message = app.getString(R.string.restore_done_choose_folder)
        waitFor(message)
        compose.onNodeWithText(message)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        waitFor("Kaiju No. 8")
        waitUntilGone(app.getString(R.string.restore_backup))
    }

    @Test
    fun `dado uma pasta escolhida ainda vazia, quando restauro, então a pasta é lida de novo e a obra local volta lida com o mesmo identificador`() {
        val folder = showLibrary(emptyList())
        waitFor(app.getString(R.string.no_works_found))

        pickBackup(BackupFormat.write(backup))
        waitFor(app.getString(R.string.restore_confirm_title))
        // The works were copied to the new phone's folder meanwhile.
        folder.titles = listOf("Sakamoto Days")
        compose.onNodeWithText(app.getString(R.string.restore_confirm)).performClick()

        waitFor(app.getString(R.string.restore_done))
        waitFor("Sakamoto Days")
        assertEquals("work-1", app.store.works().first { it.title == "Sakamoto Days" }.id)
        compose.onNodeWithText("Sakamoto Days").performClick()
        waitFor(app.getString(R.string.chapter_read))
    }

    @Test
    fun `dado a confirmação aberta, quando cancelo, então nada é restaurado e o app continua vazio`() {
        showWithoutFolder()

        pickBackup(BackupFormat.write(backup))
        waitFor(app.getString(R.string.restore_confirm_title))
        compose.onNodeWithText(app.getString(R.string.restore_cancel)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(app.getString(R.string.restore_confirm_title)).assertDoesNotExist()
        assertTrue(app.store.isEmpty())
        compose.onNodeWithText(app.getString(R.string.restore_backup)).assertExists()
    }

    @Test
    fun `dado um arquivo que não é um backup, quando escolho, então o app diz que não consegue ler e nada muda`() {
        showWithoutFolder()

        pickBackup("Capítulo 1 de Dom Casmurro")

        val message = app.getString(R.string.restore_unreadable)
        waitFor(message)
        compose.onNodeWithText(message)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue(app.store.isEmpty())
    }

    @Test
    fun `dado uma biblioteca com obras, então a Biblioteca não oferece restaurar`() {
        showLibrary(listOf("Obra"))
        waitFor("Obra")

        // Whether the app is empty is asked after each change to the works, off the main thread.
        waitUntilGone(app.getString(R.string.restore_backup))
        assertFalse(app.store.isEmpty())
    }

    @Test
    fun `dado um servidor do Kavita restaurado sem chave, quando abro a Biblioteca, então ela pede a chave e a tela do Kavita já traz o endereço`() {
        app.store.restore(backup)
        showWithoutFolder()

        waitFor(app.getString(R.string.kavita_key_again, "http://kavita.example:5000"))
        compose.onNodeWithText(app.getString(R.string.kavita_read_failed)).assertDoesNotExist()
        openTab(R.string.nav_settings)
        compose.onNodeWithText(app.getString(R.string.kavita_connect)).performClick()

        waitFor(app.getString(R.string.kavita_address))
        compose.onNodeWithText("http://kavita.example:5000").assertExists()
    }

    // Found by the external review of 18/11/2026.

    @Test
    fun `dado um servidor restaurado pedindo a chave, quando conecto na tela do Kavita e volto, então o aviso da chave some`() {
        app.store.restore(backup)
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "restored"))
        val folder = FolderInMemory(app, emptyList()).apply { chosen = false }
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, access, sync, "test-device", app.restores) }
        val askingForKey = app.getString(R.string.kavita_key_again, "http://kavita.example:5000")
        waitFor(askingForKey)

        openTab(R.string.nav_settings)
        compose.onNodeWithText(app.getString(R.string.kavita_connect)).performClick()
        waitFor(app.getString(R.string.kavita_address))
        sync.connect("http://kavita.example:5000", "key-of-kavita-1234")
        compose.onNodeWithText(app.getString(R.string.back)).performClick()
        openTab(R.string.nav_library)

        waitFor("Kaiju No. 8")
        waitUntilGone(askingForKey)
    }

    @Test
    fun `dado um backup só com obras que tinham saído da pasta, quando restauro, então Restaurar backup some`() {
        showWithoutFolder()
        val gone = backup.copy(servers = emptyList(), works = backup.works.filter { it.origin == "local" }.map { it.copy(present = false) })

        pickBackup(BackupFormat.write(gone))
        waitFor(app.getString(R.string.restore_confirm_title))
        compose.onNodeWithText(app.getString(R.string.restore_confirm)).performClick()
        waitFor(app.getString(R.string.restore_done_choose_folder))

        waitUntilGone(app.getString(R.string.restore_backup))
    }
}
