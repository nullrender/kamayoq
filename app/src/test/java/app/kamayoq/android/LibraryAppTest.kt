package app.kamayoq.android

import android.provider.DocumentsContract
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
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
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
        compose.setContent { LibraryApp(app.store, folder, opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
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
        compose.setContent { LibraryApp(app.store, folder, opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
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
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device") }
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
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, sync, "test-device") }
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

    // Step 4, milestone B: a compiled novel is one file, shown as the chapters of its table of contents.

    private val compiledNovel = "Novels/Romance/Romance.epub"

    private fun importNovel(titles: List<String>, modified: Long = 1) {
        val units = titles.mapIndexed { index, title ->
            ReadingUnit(compiledNovel, title, UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = if (titles.size == 1) "" else "OEBPS/c$index.xhtml")
        }
        val work = Work("Novels/Romance", "Romance", null, WorkType.NOVELS, null, units)
        app.store.importLocal("content://test/tree/other", LocalLibrary(listOf(work), emptyList()), mapOf(compiledNovel to FileStamp(10, modified)))
    }

    @Test
    fun `dado um romance compilado na pasta, quando a Biblioteca lê a pasta, então a obra mostra os capítulos do sumário do arquivo`() {
        val folder = FolderInMemory(app, emptyList()).apply { novels = listOf("Novela em volumes") }
        shadowOf(app.contentResolver).registerInputStream(
            DocumentsContract.buildDocumentUriUsingTree(folder.tree, folder.novelFileId("Novela em volumes")),
            ByteArrayInputStream(EpubFixtures.novel()),
        )
        compose.setContent { LibraryApp(app.store, folder, app.opening, app.backups, app.kavita, app.kavitaSync, "test-device") }
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
        showWithoutFolder()
        waitFor("Romance")
        compose.onNodeWithText("Romance").performClick()
        val question = app.getString(R.string.edition_changed, "Capítulo 1")
        waitFor(question)
        compose.onNodeWithText(app.getString(R.string.chapter_read)).assertDoesNotExist()

        compose.onNodeWithText(app.getString(R.string.edition_apply)).performClick()

        waitFor(app.getString(R.string.chapter_read))
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `dado a pergunta do arquivo que mudou, quando escolho não aplicar, então a pergunta some e nada fica lido`() {
        importNovel(listOf("Capítulo 1", "Capítulo 2"))
        val read = ChapterProgress(ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END), null, 1)
        app.store.saveProgress(app.store.chapters(app.store.works().single().id).first().id, read)
        importNovel(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), modified = 2)
        showWithoutFolder()
        waitFor("Romance")
        compose.onNodeWithText("Romance").performClick()
        val question = app.getString(R.string.edition_changed, "Capítulo 1")
        waitFor(question)

        compose.onNodeWithText(app.getString(R.string.edition_dismiss)).performClick()

        compose.waitUntil(30_000) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(app.getString(R.string.chapter_read)).assertDoesNotExist()
    }

    @Test
    fun `dado um romance compilado cujo arquivo não traz sumário, quando abro a obra, então ela avisa que o arquivo não traz a divisão`() {
        importNovel(listOf("Romance"))
        showWithoutFolder()
        waitFor("Romance")

        compose.onNodeWithText("Romance").performClick()

        waitFor(app.getString(R.string.file_undivided))
    }
}
