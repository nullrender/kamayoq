package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.backup.BackupAgreement
import app.kamayoq.core.backup.BackupAlternative
import app.kamayoq.core.backup.BackupOutbox
import app.kamayoq.core.backup.BackupServer
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import java.io.File
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-test", ".db").also { it.delete() }
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    /** Opens the database file the way the app does: creates the schema the first time. */
    private fun open(): LibraryStore {
        val properties = Properties().apply { put("foreign_keys", "true") }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", properties, LibraryStore.schema)
        drivers += driver
        return LibraryStore(driver)
    }

    /** Runs SQL the store would never run, to check what the database itself refuses. */
    private fun rawExecute(sql: String) {
        drivers.last().execute(null, sql, 0)
    }

    /** Closes every connection, as when the app's process is killed. */
    private fun closeAll() {
        drivers.forEach { it.close() }
        drivers.clear()
    }

    @AfterTest
    fun cleanUp() {
        closeAll()
        file.delete()
    }

    private fun LibraryStore.import(vararg paths: String) = importStamped(emptyMap(), *paths)

    /** Imports with [STAMP] for every file, except those given a stamp of their own. */
    private fun LibraryStore.importStamped(stamps: Map<String, FileStamp>, vararg paths: String) =
        importLocal(source, FolderConvention.read(paths.toList()), paths.associateWith { stamps[it] ?: STAMP })

    private fun LibraryStore.work(title: String) = works().single { it.title == title }

    private fun LibraryStore.chapter(work: String, name: String) = chapters(work(work).id).single { it.name == name }

    private val read = ChapterProgress(
        readMark = ReadMark(Instant.parse("2026-10-25T14:10:00Z"), ReadMarkSource.READ_TO_END),
        position = StripPosition(40, 40, Instant.parse("2026-10-25T14:10:00Z"), "phone-a"),
        completeReads = 1,
    )

    private val halfway = ChapterProgress(
        readMark = null,
        position = StripPosition(12, 40, Instant.parse("2026-10-25T14:20:00Z"), "phone-a"),
        completeReads = 0,
    )

    @Test
    fun `dado o progresso de um capítulo gravado, quando o banco fecha e abre de novo, então o progresso é o mesmo`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        store.saveProgress(chapter.id, read)

        closeAll()
        val reopened = open()

        assertEquals(read, reopened.progress(chapter.id))
    }

    @Test
    fun `dado um capítulo nunca aberto, então o progresso é nunca aberto`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(store.chapter("Sakamoto Days", "Cap. 001").id))
    }

    @Test
    fun `dado um progresso já gravado, quando gravado de novo com outro valor, então vale o último`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")

        store.saveProgress(chapter.id, halfway)
        store.saveProgress(chapter.id, read)

        assertEquals(read, store.progress(chapter.id))
    }

    @Test
    fun `dado uma leitura de pasta que deixou de ser a escolhida, quando ela termina por último, então as obras dessa pasta não voltam`() {
        val store = open()
        val other = "content://com.android.externalstorage.documents/tree/primary%3AOutra"
        val before = listOf("Comics/Sakamoto Days/Cap. 001.cbz")
        val after = listOf("Comics/A Torre de Deus/Cap. 0928.cbz")
        store.importLocal(source, FolderConvention.read(before), before.associateWith { STAMP })

        // Another folder is chosen: the first one is forgotten and the new one read.
        store.importLocal(source, FolderConvention.read(emptyList()), emptyMap())
        store.importLocal(other, FolderConvention.read(after), after.associateWith { STAMP })
        // The reading of the first folder, started before the change, ends last.
        store.importLocal(source, FolderConvention.read(before), before.associateWith { STAMP }, stillChosen = { false })

        assertEquals(listOf("A Torre de Deus"), store.works().map { it.title })
    }

    @Test
    fun `dado a pasta de uma obra renomeada só na maiúscula, quando lida de novo, então é a mesma obra com o mesmo capítulo e progresso`() {
        val store = open()
        store.import("comics/sakamoto days/Cap. 001.cbz")
        val before = store.chapter("sakamoto days", "Cap. 001")
        store.saveProgress(before.id, halfway)

        // Decided on 30/10/2026: letter case alone is not a new work.
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        val after = store.chapter("Sakamoto Days", "Cap. 001")
        assertEquals(before.id, after.id)
        assertEquals("Comics/Sakamoto Days/Cap. 001.cbz", after.path)
        assertEquals(halfway, store.progress(after.id))
        assertEquals(1, store.works().size)
    }

    @Test
    fun `dado obras de duas pastas guardadas, quando só uma fica escolhida, então a lista mostra só ela e a outra volta se for escolhida de novo`() {
        val store = open()
        val other = "content://com.android.externalstorage.documents/tree/primary%3AOutra"
        val first = listOf("Comics/Sakamoto Days/Cap. 001.cbz")
        val second = listOf("Comics/A Torre de Deus/Cap. 0928.cbz")
        store.importLocal(source, FolderConvention.read(first), first.associateWith { STAMP })
        store.importLocal(other, FolderConvention.read(second), second.associateWith { STAMP })

        // The app may have closed between choosing a folder and leaving the other.
        store.keepOnlyFolder(other)
        assertEquals(listOf("A Torre de Deus"), store.works().map { it.title })

        store.keepOnlyFolder(source)
        store.importLocal(source, FolderConvention.read(first), first.associateWith { STAMP })
        assertEquals(listOf("Sakamoto Days"), store.works().map { it.title })
    }

    @Test
    fun `dado duas pastas e dois arquivos que só diferem na maiúscula na mesma leitura, então cada um continua o seu`() {
        val store = open()
        store.import("Comics/X/Cap. 1.cbz")
        val first = store.chapter("X", "Cap. 1").id

        // Both exist side by side on a provider that tells letter case apart.
        store.import("Comics/X/Cap. 1.cbz", "Comics/X/cap. 1.cbz", "Comics/x/Cap. 1.cbz")

        assertEquals(setOf("X", "x"), store.works().map { it.title }.toSet())
        assertEquals(first, store.chapter("X", "Cap. 1").id)
        assertEquals(2, store.chapters(store.work("X").id).size)
    }

    @Test
    fun `dado a pasta renomeada na maiúscula e um arquivo novo que difere do antigo só na maiúscula, então o antigo continua com o seu progresso`() {
        val store = open()
        store.import("Comics/x/cap. 1.cbz")
        val original = store.chapter("x", "cap. 1").id
        store.saveProgress(original, halfway)

        // The new file sorts first; the old one must keep its identity.
        store.import("Comics/X/Cap. 1.cbz", "Comics/X/cap. 1.cbz")

        assertEquals(original, store.chapter("X", "cap. 1").id)
        assertEquals(halfway, store.progress(original))
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(store.chapter("X", "Cap. 1").id))
    }

    @Test
    fun `dado um capítulo ausente quando a pasta mudou de maiúscula, quando ele volta, então volta com o seu progresso`() {
        val store = open()
        store.import("Comics/x/Cap. 1.cbz", "Comics/x/Cap. 2.cbz")
        val away = store.chapter("x", "Cap. 1").id
        store.saveProgress(away, halfway)

        // Its stored path keeps the old letter case while it is gone.
        store.import("Comics/X/Cap. 2.cbz")
        store.import("Comics/X/Cap. 1.cbz", "Comics/X/Cap. 2.cbz")

        assertEquals(away, store.chapter("X", "Cap. 1").id)
        assertEquals(halfway, store.progress(away))
    }

    @Test
    fun `dado a pasta de uma obra renomeada só na maiúscula de uma letra acentuada, então é a mesma obra`() {
        val store = open()
        store.import("Comics/árvore/Cap. 1.cbz")
        val before = store.chapter("árvore", "Cap. 1").id

        // SQLite's NOCASE folds only A to Z.
        store.import("Comics/Árvore/Cap. 1.cbz")

        assertEquals(before, store.chapter("Árvore", "Cap. 1").id)
    }

    @Test
    fun `dado uma leitura que deixou de ser a mais nova, quando vai deixar só a sua pasta, então não esconde a pasta escolhida depois`() {
        val store = open()
        val other = "content://com.android.externalstorage.documents/tree/primary%3AOutra"
        val paths = listOf("Comics/Sakamoto Days/Cap. 001.cbz")
        store.importLocal(other, FolderConvention.read(paths), paths.associateWith { STAMP })

        // A reading that is no longer the newest must not hide a folder chosen after it.
        store.keepOnlyFolder(source, stillChosen = { false })

        assertEquals(listOf("Sakamoto Days"), store.works().map { it.title })
    }

    @Test
    fun `dado um capítulo aberto pelo número antigo, então a edição atual e a lista vêm do mesmo momento`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001").id
        store.confirmContent(old, "hash-1")
        store.saveProgress(old, read)
        store.importStamped(changed, cap1)
        val edition = store.confirmContent(old, "hash-2")

        // A list read apart from the edition could miss it.
        val opening = store.openingOf(store.work("Sakamoto Days").id, old)

        assertEquals(edition, opening.current)
        assertEquals(listOf(edition), opening.chapters.map { it.id })
    }

    @Test
    fun `dado uma pasta de imagens cuja abertura foi superada enquanto lia as imagens, quando vai confirmar, então nada muda no banco`() {
        val store = open()
        store.import("Comics/X/Cap. 002/001.jpg")
        val chapter = store.chapter("X", "Cap. 002").id
        store.confirmContent(chapter, "hash-1")
        store.saveProgress(chapter, halfway)

        // The check comes after the slow hash, around the write.
        assertFailsWith<IllegalStateException> {
            store.confirmFolder(
                chapter,
                stampNow = "folder:1:9999:1:1",
                hash = { "hash-2" },
                hashBeforeNames = { "hash-3" },
                whileLatest = { error("A later chapter was opened") },
            )
        }

        assertEquals(chapter, store.currentEdition(chapter))
    }

    @Test
    fun `dado uma pasta de imagens conferida com o resumo antigo, quando o conteúdo é o mesmo, então não vira edição nova`() {
        val store = open()
        store.import("Comics/X/Cap. 002/001.jpg")
        val chapter = store.chapter("X", "Cap. 002").id
        store.confirmContent(chapter, "summary-with-the-whole-path")
        store.saveProgress(chapter, halfway)

        // The summary stopped counting the folder's path; what was read before keeps its progress.
        val opened = store.confirmFolder(
            chapter,
            stampNow = "folder:1:9999:1:1",
            hash = { "summary-with-the-page-name" },
            hashBeforeNames = { "summary-with-the-whole-path" },
        )

        assertEquals(chapter, opened)
        assertEquals(halfway, store.progress(chapter))
    }

    @Test
    fun `dado obras importadas, então saem com título, título alternativo, tipo e subtipo, na ordem da pasta`() {
        val store = open()
        val paths = listOf("Comics/A Torre de Deus (Tower of God)/Cap. 0928.cbz", "Comics/Sakamoto Days/Cap. 001.cbz")
        val library = FolderConvention.read(paths)
        // No folder gives a subtype any more (decision of 27/10/2026); it comes from metadata, set here as it would.
        val withSubtype = library.copy(
            works = library.works.map { if (it.title == "Sakamoto Days") it.copy(subtype = ComicsSubtype.MANHWA) else it },
        )
        store.importLocal(source, withSubtype, paths.associateWith { STAMP })

        assertEquals(
            listOf(
                Triple("A Torre de Deus", "Tower of God", WorkType.COMICS to null),
                Triple("Sakamoto Days", null, WorkType.COMICS to ComicsSubtype.MANHWA),
            ),
            store.works().map { Triple(it.title, it.alternativeTitle, it.type to it.subtype) },
        )
    }

    @Test
    fun `dado os capítulos importados, então saem do banco na ordem da convenção, com tipo, número e formato`() {
        val store = open()
        store.import(
            "Comics/Gantz/Cap. 099.cbz",
            "Comics/Gantz/Extra.cbz",
            "Comics/Gantz/Cap. 100/01.jpg",
            "Comics/Gantz/Vol. 01.cbz",
        )

        val chapters = store.chapters(store.work("Gantz").id)

        assertEquals(listOf("Vol. 01", "Cap. 099", "Cap. 100", "Extra"), chapters.map { it.name })
        assertEquals(listOf(UnitKind.VOLUME, UnitKind.CHAPTER, UnitKind.CHAPTER, UnitKind.CHAPTER), chapters.map { it.kind })
        assertEquals(listOf(1.0, 99.0, 100.0, null), chapters.map { it.number })
        assertEquals(
            listOf(ChapterFormat.ARCHIVE, ChapterFormat.ARCHIVE, ChapterFormat.IMAGE_FOLDER, ChapterFormat.ARCHIVE),
            chapters.map { it.format },
        )
        assertEquals("Comics/Gantz/Cap. 100", chapters[2].path)
    }

    @Test
    fun `dado a mesma pasta importada duas vezes, então obras e capítulos mantêm os identificadores e o progresso`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val work = store.work("Sakamoto Days")
        val chapters = store.chapters(work.id)
        store.saveProgress(chapters[0].id, read)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        assertEquals(listOf(work), store.works())
        assertEquals(chapters, store.chapters(work.id))
        assertEquals(read, store.progress(chapters[0].id))
    }

    @Test
    fun `dado um capítulo novo na pasta, quando reimportada, então ele entra na ordem sem mexer no progresso dos outros`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 003.cbz")
        val first = store.chapter("Sakamoto Days", "Cap. 001")
        store.saveProgress(first.id, read)

        store.import(
            "Comics/Sakamoto Days/Cap. 001.cbz",
            "Comics/Sakamoto Days/Cap. 002.cbz",
            "Comics/Sakamoto Days/Cap. 003.cbz",
        )

        val chapters = store.chapters(store.work("Sakamoto Days").id)
        assertEquals(listOf("Cap. 001", "Cap. 002", "Cap. 003"), chapters.map { it.name })
        assertEquals(first.id, chapters[0].id)
        assertEquals(read, store.progress(first.id))
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(chapters[1].id))
    }

    @Test
    fun `dado um capítulo que sumiu da pasta, quando reimportada, então sai da lista e o progresso fica guardado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val second = store.chapter("Sakamoto Days", "Cap. 002")
        store.saveProgress(second.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(listOf("Cap. 001"), store.chapters(store.work("Sakamoto Days").id).map { it.name })
        assertEquals(halfway, store.progress(second.id))
    }

    @Test
    fun `dado um capítulo que sumiu e voltou ao mesmo lugar, então volta com o mesmo identificador e o progresso`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val second = store.chapter("Sakamoto Days", "Cap. 002")
        store.saveProgress(second.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        assertEquals(second, store.chapter("Sakamoto Days", "Cap. 002"))
        assertEquals(halfway, store.progress(second.id))
    }

    @Test
    fun `dado uma obra que sumiu da pasta, quando reimportada, então sai da lista e volta com o progresso se a pasta voltar`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 099.cbz")
        val work = store.work("Gantz")
        val chapter = store.chapter("Gantz", "Cap. 099")
        store.saveProgress(chapter.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        assertEquals(listOf("Sakamoto Days"), store.works().map { it.title })

        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 099.cbz")
        assertEquals(work, store.work("Gantz"))
        assertEquals(halfway, store.progress(chapter.id))
    }

    @Test
    fun `dado uma obra nunca lida, então não há capítulo para reabrir`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertNull(store.resumeChapter(store.work("Sakamoto Days").id))
    }

    @Test
    fun `dado o progresso gravado no capítulo 2, então a obra reabre no capítulo 2, também depois de fechar o banco`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        store.saveProgress(store.chapter("Sakamoto Days", "Cap. 001").id, read)
        val second = store.chapter("Sakamoto Days", "Cap. 002")
        store.saveProgress(second.id, halfway)

        closeAll()
        val reopened = open()

        assertEquals(second, reopened.resumeChapter(reopened.work("Sakamoto Days").id))
    }

    @Test
    fun `dado uma posição impossível escrita direto no banco, então o banco recusa`() {
        // The schema used to accept page -1, total 0 and reads -1.
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val id = store.chapter("Sakamoto Days", "Cap. 001").id
        val columns = "chapter_id, read_at, read_source, position_page, position_total, position_at, " +
            "position_device, complete_reads"

        listOf(
            "'$id', NULL, NULL, -1, 40, 0, 'phone-a', 0",
            "'$id', NULL, NULL, 41, 40, 0, 'phone-a', 0",
            "'$id', NULL, NULL, 0, 0, 0, 'phone-a', 0",
            "'$id', NULL, NULL, NULL, NULL, NULL, NULL, -1",
            "'$id', NULL, NULL, 3, 40, NULL, 'phone-a', 0",
            "'$id', 0, NULL, NULL, NULL, NULL, NULL, 0",
        ).forEach { values ->
            assertFails("accepted: $values") { rawExecute("INSERT INTO chapter_progress ($columns) VALUES ($values)") }
        }
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(id))
    }

    @Test
    fun `dado uma obra, quando o banco recebe para ela um capítulo a reabrir de outra obra, então recusa`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 099.cbz")
        val other = store.chapter("Gantz", "Cap. 099").id

        assertFails {
            rawExecute(
                "INSERT INTO work_resume (version_id, chapter_id) " +
                    "SELECT id, '$other' FROM work_version WHERE path = 'Comics/Sakamoto Days'",
            )
        }
        assertNull(store.resumeChapter(store.work("Sakamoto Days").id))
    }

    @Test
    fun `dado o capítulo onde a obra reabre, quando some da pasta, então a reabertura aponta para ele marcado como ausente`() {
        // The resume used to point at a missing chapter as if it were there.
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val second = store.chapter("Sakamoto Days", "Cap. 002")
        store.saveProgress(second.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(second.copy(present = false), store.resumeChapter(store.work("Sakamoto Days").id))
    }

    @Test
    fun `dado o capítulo onde a obra reabre, quando volta à pasta, então a reabertura volta a ele presente`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val second = store.chapter("Sakamoto Days", "Cap. 002")
        store.saveProgress(second.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        assertEquals(second, store.resumeChapter(store.work("Sakamoto Days").id))
    }

    @Test
    fun `dado uma obra que sumiu da pasta, então os capítulos dela saem da lista e a reabertura fica ausente`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 099.cbz")
        val work = store.work("Gantz")
        val chapter = store.chapter("Gantz", "Cap. 099")
        store.saveProgress(chapter.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(emptyList(), store.chapters(work.id))
        assertEquals(chapter.copy(present = false), store.resumeChapter(work.id))
    }

    // The same path used to be taken for the same content (rule E1).

    private val cap1 = "Comics/Sakamoto Days/Cap. 001.cbz"
    private val changed = mapOf(cap1 to FileStamp(2_000, 9))

    @Test
    fun `dado um capítulo novo, então ele pede conferência até a primeira abertura guardar o resumo`() {
        val store = open()
        store.import(cap1)
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        assertTrue(chapter.needsContentCheck)

        assertEquals(chapter.id, store.confirmContent(chapter.id, "hash-1"))

        assertFalse(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
        store.import(cap1)
        assertFalse(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
    }

    @Test
    fun `dado a impressão diferente, quando o conteúdo conferido é o mesmo, então é o mesmo capítulo com o progresso`() {
        val store = open()
        store.import(cap1)
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(chapter.id, "hash-1")
        store.saveProgress(chapter.id, halfway)

        store.importStamped(changed, cap1)
        assertTrue(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)

        assertEquals(chapter.id, store.confirmContent(chapter.id, "hash-1"))
        assertEquals(halfway, store.progress(chapter.id))
        assertFalse(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
    }

    @Test
    fun `dado a impressão diferente, quando o conteúdo conferido é outro, então nasce um capítulo do zero e o antigo guarda o progresso`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(old.id, "hash-1")
        store.saveProgress(old.id, read)

        store.importStamped(changed, cap1)
        val newId = store.confirmContent(old.id, "hash-2")

        assertNotEquals(old.id, newId)
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(newId))
        store.saveProgress(newId, halfway)
        assertEquals(read, store.progress(old.id))
        assertEquals(listOf(newId), store.chapters(store.work("Sakamoto Days").id).map { it.id })
        store.importStamped(changed, cap1)
        assertEquals(listOf(newId), store.chapters(store.work("Sakamoto Days").id).map { it.id })
    }

    @Test
    fun `dado um capítulo que virou edição nova duas vezes, quando procurado pelo número antigo, então dá a edição atual`() {
        val store = open()
        store.import(cap1)
        val first = store.chapter("Sakamoto Days", "Cap. 001").id
        store.confirmContent(first, "hash-1")
        store.saveProgress(first, read)
        store.importStamped(changed, cap1)
        val second = store.confirmContent(first, "hash-2")
        store.saveProgress(second, halfway)
        val third = store.confirmContent(second, "hash-3")

        assertEquals(third, store.currentEdition(first))
        assertEquals(third, store.currentEdition(second))
        assertEquals(third, store.currentEdition(third))
    }

    @Test
    fun `dado um capítulo que já virou edição nova, quando conferido de novo pelo número antigo com o mesmo conteúdo, então devolve a edição atual sem criar outra`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001").id
        store.confirmContent(old, "hash-1")
        store.saveProgress(old, read)
        store.importStamped(changed, cap1)
        val edition = store.confirmContent(old, "hash-2")

        // A second opening that read the list before the first one confirmed.
        assertEquals(edition, store.confirmContent(old, "hash-2"))
        assertEquals(listOf(edition), store.chapters(store.work("Sakamoto Days").id).map { it.id })
        assertEquals(read, store.progress(old))
    }

    @Test
    fun `dado um capítulo que já virou edição nova, quando conferido de novo pelo número antigo com outro conteúdo, então a edição atual vira anterior`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001").id
        store.confirmContent(old, "hash-1")
        store.saveProgress(old, read)
        store.importStamped(changed, cap1)
        val edition = store.confirmContent(old, "hash-2")
        store.saveProgress(edition, halfway)

        val third = store.confirmContent(old, "hash-3")

        assertNotEquals(edition, third)
        assertEquals(third, store.currentEdition(edition))
        assertEquals(listOf(third), store.chapters(store.work("Sakamoto Days").id).map { it.id })
        assertEquals(halfway, store.progress(edition))
    }

    @Test
    fun `dado uma pasta de imagens trocada antes da leitura da pasta, quando aberta, então o resumo é conferido e o progresso antigo fica na edição anterior`() {
        val store = open()
        store.import("Comics/X/Cap. 002/001.jpg", "Comics/X/Cap. 002/002.jpg")
        val old = store.chapter("X", "Cap. 002").id
        store.confirmContent(old, "hash-1")
        store.saveProgress(old, halfway)

        // The images changed with the app closed; the reading of the folder has not marked it yet.
        val opened = store.confirmFolder(old, stampNow = "folder:2:9999:1", hash = { "hash-2" }, hashBeforeNames = { "hash-3" })

        assertNotEquals(old, opened)
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(opened))
        assertEquals(halfway, store.progress(old))
    }

    @Test
    fun `dado uma pasta de imagens que ficou vazia, quando aberta, então não vira edição nova e o progresso continua nela`() {
        val store = open()
        store.import("Comics/X/Cap. 002/001.jpg")
        val chapter = store.chapter("X", "Cap. 002").id
        store.confirmContent(chapter, "hash-1")
        store.saveProgress(chapter, halfway)
        store.importStamped(mapOf("Comics/X/Cap. 002/001.jpg" to changed.values.single()), "Comics/X/Cap. 002/001.jpg")

        // A sync emptied the folder while it opened.
        val opened = store.confirmFolder(chapter, stampNow = null, hash = { "hash-of-nothing" }, hashBeforeNames = { "hash-of-nothing" })

        assertEquals(chapter, opened)
        assertEquals(halfway, store.progress(chapter))
        assertEquals(listOf(chapter), store.chapters(store.work("X").id).map { it.id })
    }

    @Test
    fun `dado um capítulo que sumiu e voltou com o mesmo conteúdo, quando conferido, então volta com o progresso`() {
        val store = open()
        store.import(cap1, "Comics/Sakamoto Days/Cap. 002.cbz")
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(chapter.id, "hash-1")
        store.saveProgress(chapter.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 002.cbz")
        store.import(cap1, "Comics/Sakamoto Days/Cap. 002.cbz")

        assertTrue(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
        assertEquals(chapter.id, store.confirmContent(chapter.id, "hash-1"))
        assertEquals(halfway, store.progress(chapter.id))
    }

    @Test
    fun `dado um capítulo que sumiu e voltou com outro conteúdo, quando conferido, então nasce um capítulo do zero`() {
        val store = open()
        store.import(cap1, "Comics/Sakamoto Days/Cap. 002.cbz")
        val old = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(old.id, "hash-1")
        store.saveProgress(old.id, halfway)

        store.import("Comics/Sakamoto Days/Cap. 002.cbz")
        store.import(cap1, "Comics/Sakamoto Days/Cap. 002.cbz")
        val newId = store.confirmContent(old.id, "hash-2")

        assertNotEquals(old.id, newId)
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(newId))
        assertEquals(halfway, store.progress(old.id))
    }

    @Test
    fun `dado a pasta inteira de uma obra que sumiu e voltou igual, quando lida, então o capítulo com resumo volta para conferir`() {
        val store = open()
        store.import(cap1, "Comics/Outra/Cap. 1.cbz")
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(chapter.id, "hash-1")

        store.import("Comics/Outra/Cap. 1.cbz")
        store.import(cap1, "Comics/Outra/Cap. 1.cbz")

        // The stamp is the same; only the return asks for a check.
        assertTrue(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
    }

    @Test
    fun `dado uma impressão mudada ainda não conferida, quando a pasta é lida de novo igual, então continua marcado para conferir`() {
        val store = open()
        store.import(cap1)
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(chapter.id, "hash-1")

        store.importStamped(changed, cap1)
        store.importStamped(changed, cap1)

        // Only the hash clears the mark.
        assertTrue(store.chapter("Sakamoto Days", "Cap. 001").needsContentCheck)
    }

    @Test
    fun `dado um capítulo nunca aberto com a impressão diferente, quando conferido, então é aceito sem virar edição nova`() {
        val store = open()
        store.import(cap1)
        val chapter = store.chapter("Sakamoto Days", "Cap. 001")

        store.importStamped(changed, cap1)

        assertEquals(chapter.id, store.confirmContent(chapter.id, "hash-2"))
    }

    @Test
    fun `dado um progresso sem resumo guardado, quando a impressão muda, então na dúvida o antigo vira edição anterior`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001")
        store.saveProgress(old.id, read)

        store.importStamped(changed, cap1)
        val newId = store.confirmContent(old.id, "hash-2")

        assertNotEquals(old.id, newId)
        assertEquals(read, store.progress(old.id))
    }

    private val exportedAt = Instant.parse("2026-10-28T21:48:00Z")

    @Test
    fun `dado um capítulo lido e outro pela metade, quando exportados, então a cópia traz a obra, o progresso de cada um e onde ela reabre`() {
        val store = open()
        val cap2 = "Comics/Sakamoto Days/Cap. 002.cbz"
        store.import(cap1, cap2)
        store.saveProgress(store.chapter("Sakamoto Days", "Cap. 001").id, read)
        store.saveProgress(store.chapter("Sakamoto Days", "Cap. 002").id, halfway)

        val backup = store.backup(appVersion = "r100", exportedAt = exportedAt)

        assertEquals("r100", backup.appVersion)
        assertEquals(exportedAt, backup.exportedAt)
        val work = backup.works.single()
        assertEquals(listOf("local", "Comics/Sakamoto Days", "Sakamoto Days"), listOf(work.origin, work.path, work.title))
        assertEquals(WorkType.COMICS, work.type)
        assertTrue(work.present)
        assertEquals(listOf(cap1, cap2), work.chapters.map { it.path })
        assertEquals(listOf(read, halfway), work.chapters.map { it.progress })
        assertEquals(listOf(false, true), work.chapters.map { it.resume })
        assertEquals(listOf(true, true), work.chapters.map { it.present })
    }

    @Test
    fun `dado uma obra, quando exportada, então a cópia leva o identificador da obra e o da versão`() {
        val store = open()
        store.import(cap1)

        val work = store.backup("r100", exportedAt).works.single()

        // A restored work must keep the identity that never changes.
        assertEquals(store.work("Sakamoto Days").id, work.id)
        assertTrue(work.versionId.isNotEmpty())
    }

    @Test
    fun `dado uma obra nunca aberta, quando exportada, então os capítulos vão sem progresso e sem lugar para reabrir`() {
        val store = open()
        store.import(cap1)

        val chapter = store.backup("r100", exportedAt).works.single().chapters.single()

        assertEquals(ChapterProgress.NEVER_OPENED, chapter.progress)
        assertFalse(chapter.resume)
        assertNull(chapter.contentHash)
    }

    @Test
    fun `dado uma obra que sumiu da pasta, quando exportada, então vai na cópia como ausente, com o progresso`() {
        val store = open()
        store.import(cap1, "Comics/Gantz/Cap. 001.cbz")
        store.saveProgress(store.chapter("Sakamoto Days", "Cap. 001").id, read)
        store.import("Comics/Gantz/Cap. 001.cbz")

        val gone = store.backup("r100", exportedAt).works.single { it.title == "Sakamoto Days" }

        assertFalse(gone.present)
        // The chapter's own presence: its file was there the last time the work was seen.
        assertEquals(listOf(true), gone.chapters.map { it.present })
        assertEquals(listOf(read), gone.chapters.map { it.progress })
        assertEquals(listOf(true), gone.chapters.map { it.resume })
    }

    @Test
    fun `dado uma edição anterior de um capítulo, quando exportada, então as duas vão no mesmo caminho, cada uma com o seu resumo e progresso`() {
        val store = open()
        store.import(cap1)
        val old = store.chapter("Sakamoto Days", "Cap. 001")
        store.confirmContent(old.id, "hash-1")
        store.saveProgress(old.id, read)
        store.importStamped(changed, cap1)
        val newId = store.confirmContent(old.id, "hash-2")
        store.saveProgress(newId, halfway)

        val chapters = store.backup("r100", exportedAt).works.single().chapters

        assertEquals(listOf(cap1, cap1), chapters.map { it.path })
        assertEquals(listOf(old.id, newId), chapters.map { it.id })
        assertEquals(listOf(newId, null), chapters.map { it.replacedBy })
        assertEquals(listOf("hash-1", "hash-2"), chapters.map { it.contentHash })
        assertEquals(listOf(false, true), chapters.map { it.present })
        assertEquals(listOf(read, halfway), chapters.map { it.progress })
        assertEquals(listOf(false, true), chapters.map { it.resume })
    }

    @Test
    fun `dado edições A, B e A de novo numa obra que sumiu, depois de mudar a ordem, então a cópia diz qual substituiu qual e qual é a atual`() {
        val store = open()
        store.import(cap1)
        val first = store.chapter("Sakamoto Days", "Cap. 001").id
        // Read without a hash: in doubt, the next content makes it a previous edition.
        store.saveProgress(first, read)
        val second = store.confirmContent(first, "hash-A")
        store.saveProgress(second, halfway)
        val third = store.confirmContent(second, "hash-B")
        val fourth = store.confirmContent(third, "hash-A")
        // A chapter before it moves the current edition only; then the work leaves the folder.
        store.import("Comics/Sakamoto Days/Cap. 000.cbz", cap1)
        store.import("Comics/Gantz/Cap. 001.cbz")

        val work = store.backup("r100", exportedAt).works.single { it.title == "Sakamoto Days" }
        val editions = work.chapters.filter { it.path == cap1 }.associateBy { it.id }

        assertEquals(setOf(first, second, third, fourth), editions.keys)
        assertEquals(second, editions.getValue(first).replacedBy)
        assertEquals(third, editions.getValue(second).replacedBy)
        assertEquals(fourth, editions.getValue(third).replacedBy)
        assertNull(editions.getValue(fourth).replacedBy)
        assertEquals(listOf(null, "hash-A", "hash-B", "hash-A"), listOf(first, second, third, fourth).map { editions.getValue(it).contentHash })
        assertTrue(work.chapters.all { it.format == ChapterFormat.ARCHIVE })
    }

    @Test
    fun `dado um capítulo em pasta de imagens, quando exportado, então a cópia diz que é pasta de imagens`() {
        val store = open()
        store.import("Comics/Gantz/Cap. 100/01.jpg")

        assertEquals(ChapterFormat.IMAGE_FOLDER, store.backup("r100", exportedAt).works.single().chapters.single().format)
    }

    @Test
    fun `dado Books-Dom Casmurro epub na pasta, quando guardado, então o capítulo é EPUB numa obra de Livros`() {
        val store = open()
        store.import("Books/Dom Casmurro.epub")

        val work = store.work("Dom Casmurro")
        assertEquals(WorkType.BOOKS, work.type)
        assertEquals(listOf(ChapterFormat.EPUB), store.chapters(work.id).map { it.format })
    }

    @Test
    fun `dado uma obra local, quando exportada, então a cópia não diz qual é a pasta`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertNull(store.backup("r100", exportedAt).works.single().source)
    }

    @Test
    fun `dado um servidor com obra, acordo, fila e posição alternativa, quando exportado, então a cópia leva tudo`() {
        val store = open()
        rawExecute("INSERT INTO kavita_server VALUES ('server-1', 'http://kavita.example:5000', '0.9.1.4', 1)")
        rawExecute("INSERT INTO kavita_library VALUES ('server-1', 2)")
        rawExecute("INSERT INTO work VALUES ('w', 'Sakamoto Days', NULL, 'COMICS', NULL)")
        rawExecute(
            "INSERT INTO work_version (id, work_id, origin, source, path, present, sort_index, remote_library_id) " +
                "VALUES ('v', 'w', 'kavita', 'server-1', '12', 1, 0, 2)",
        )
        rawExecute(
            "INSERT INTO chapter (id, version_id, path, name, kind, format, sort_index, present, stamp, needs_check, " +
                "page_count, remote_file, remote_volume_id) " +
                "VALUES ('c', 'v', '91', 'Cap. 1', 'CHAPTER', 'EPUB', 0, 1, '42|9', 0, 42, 'Vol. 1.epub', 7)",
        )
        rawExecute("INSERT INTO progress_agreement VALUES ('c', 3, NULL, '2026-10-25T14:10:00', 0, NULL)")
        rawExecute("INSERT INTO progress_outbox VALUES ('c', 5, NULL, NULL, 'TO_SEND', 1000)")
        rawExecute("INSERT INTO alternative_position VALUES ('v', 'CONFLICT', 'c', 3, 42, 1000, 'kavita')")

        val backup = store.backup("r100", exportedAt)

        assertEquals(listOf(BackupServer("server-1", "http://kavita.example:5000", "0.9.1.4", 1, listOf(2))), backup.servers)
        val work = backup.works.single()
        assertEquals("server-1", work.source)
        assertEquals(2, work.remoteLibraryId)
        assertEquals(listOf(BackupAlternative("CONFLICT", "c", 3, 42, Instant.ofEpochMilli(1000), "kavita")), work.alternatives)
        val chapter = work.chapters.single()
        assertEquals(ChapterFormat.EPUB, chapter.format)
        assertEquals(Triple(42, "Vol. 1.epub", 7), Triple(chapter.pageCount, chapter.remoteFile, chapter.remoteVolumeId))
        assertEquals(BackupAgreement(3, null, "2026-10-25T14:10:00", false, null), chapter.agreement)
        assertEquals(BackupOutbox(5, null, null, "TO_SEND", Instant.ofEpochMilli(1000)), chapter.outbox)
    }

    private companion object {
        /** The stamp every file gets unless a test gives it another. */
        val STAMP = FileStamp(1_000, 5)
    }
}
