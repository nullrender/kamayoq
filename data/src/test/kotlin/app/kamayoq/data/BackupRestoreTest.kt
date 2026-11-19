package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.backup.Backup
import app.kamayoq.core.backup.BackupFormat
import app.kamayoq.core.backup.BackupWork
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.Work
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Restoring a backup on an empty app (docs/decisions/backup-restore-2026-11-18.md).
class BackupRestoreTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    private fun emptyStore(): LibraryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        drivers += driver
        return LibraryStore(driver)
    }

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private val oldFolder = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val newFolder = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AKamayoq"

    private val at = Instant.parse("2026-11-18T12:00:00Z")
    private val exportedAt = Instant.parse("2026-11-18T22:00:00Z")
    private val read = ChapterProgress(ReadMark(at, ReadMarkSource.READ_TO_END), StripPosition(40, 40, at, "phone-a"), 1)
    private val halfway = ChapterProgress(null, StripPosition(17, 38, at, "phone-a"), 0)
    private val inTheBook = ChapterProgress(
        null,
        TextPosition(1, 3, "epubcfi(/6/4!/4/2)", BasisPoints.fromStored(4_000), at, "phone-a"),
        0,
    )

    private val cap1 = "Comics/Sakamoto Days/Cap. 001.cbz"
    private val cap2 = "Comics/Sakamoto Days/Cap. 002.cbz"
    private val gantz = "Comics/Gantz/Cap. 001.cbz"
    private val novel = "Novels/Release That Witch/Release That Witch.epub"

    private fun novelChapter(number: Int) =
        ReadingUnit(novel, "Capítulo $number", UnitKind.CHAPTER, number.toDouble(), UnitContent.SplitEpub, tocKey = "OEBPS/c$number.xhtml")

    private val novelWork = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, listOf(novelChapter(1), novelChapter(2)))

    /** The comics of [paths] and the compiled novel, read from [folder] with the same stamp for every file but [cap1]'s. */
    private fun LibraryStore.readFolder(folder: String, vararg paths: String, cap1Stamp: FileStamp = FileStamp(1_000, 5)) {
        val comics = FolderConvention.read(paths.toList())
        val stamps = (paths.toList() + novel).associateWith { FileStamp(1_000, 5) } + (cap1 to cap1Stamp)
        importLocal(folder, LocalLibrary(comics.works + novelWork, emptyList()), stamps)
    }

    private fun LibraryStore.workId(title: String) = works().single { it.title == title }.id

    private fun LibraryStore.chapterId(title: String, name: String) = chapters(workId(title)).single { it.name == name }.id

    /**
     * A library with every kind of row a backup carries: a read chapter with an earlier edition, one
     * halfway, a work gone from the folder, a compiled novel split in chapters with a book's place,
     * and a Kavita server with a work, its agreement, its queue and a position kept aside.
     */
    private fun fullLibrary(): LibraryStore {
        val store = emptyStore()
        store.readFolder(oldFolder, cap1, cap2, gantz)
        val first = store.chapterId("Sakamoto Days", "Cap. 001")
        store.confirmContent(first, "hash-1")
        store.saveProgress(first, read)
        store.saveProgress(store.chapterId("Gantz", "Cap. 001"), halfway)
        // Gantz leaves the folder; Cap. 001 changes content, so its reading stays with an earlier edition.
        store.readFolder(oldFolder, cap1, cap2, cap1Stamp = FileStamp(2_000, 9))
        val second = store.confirmContent(first, "hash-2")
        store.saveProgress(second, halfway)
        store.saveProgress(store.chapterId("Release That Witch", "Capítulo 2"), inTheBook)

        store.linkKavita("http://kavita.example:5000", "0.9.1.4", 1)
        val server = checkNotNull(store.kavitaServer()).id
        store.markLibraries(server, listOf(2))
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "42|100", 42, "Cap. 1.cbz", 7)
        store.importKavita(server, listOf(ImportedWork("12", "Kaiju No. 8", WorkType.COMICS, null, 2, listOf(chapter))))
        val remote = store.chapterId("Kaiju No. 8", "Cap. 1")
        store.saveProgress(remote, ChapterProgress(null, StripPosition(10, 42, at, "phone-a"), 0))
        // The server's page 30 is adopted, page 10 stays aside; then page 31 waits for the network.
        store.adoptRemote(remote, 30, 42, "2026-11-18T10:00:00", at, LibraryStore.PREVIOUS)
        store.queueProgress(remote, 31, at)
        return store
    }

    /** The backup of [store] as the file holds it: written and read back, as a restore gets it. */
    private fun fileOf(store: LibraryStore): Backup = BackupFormat.read(BackupFormat.write(store.backup("r550", exportedAt)))

    @Test
    fun `dado um app vazio, quando restauro o backup de uma biblioteca com edições, romance dividido e Kavita, então o backup do app restaurado é igual ao original`() {
        val original = fullLibrary()
        val restored = emptyStore()

        assertTrue(restored.restore(fileOf(original)))

        assertEquals(original.backup("r550", exportedAt), restored.backup("r550", exportedAt))
    }

    @Test
    fun `dado um app com uma obra, quando restauro, então recusa e nada muda`() {
        val target = emptyStore()
        target.readFolder(newFolder, gantz)
        val before = target.backup("r550", exportedAt)

        assertFalse(target.restore(fileOf(fullLibrary())))

        assertEquals(before, target.backup("r550", exportedAt))
    }

    @Test
    fun `dado um app só com o servidor do Kavita, quando restauro, então recusa`() {
        val target = emptyStore()
        target.linkKavita("http://kavita.example:5000", "0.9.1.4", 1)

        assertFalse(target.isEmpty())
        assertFalse(target.restore(fileOf(fullLibrary())))
    }

    @Test
    fun `dado uma pasta escolhida sem nenhuma obra, quando restauro, então o backup entra`() {
        val target = emptyStore()
        target.importLocal(newFolder, LocalLibrary(emptyList(), emptyList()), emptyMap())

        assertTrue(target.isEmpty())
        assertTrue(target.restore(fileOf(fullLibrary())))
        assertFalse(target.isEmpty())
    }

    @Test
    fun `dado uma biblioteca restaurada, quando a pasta do celular novo é lida, então as obras reencontram identificador e leitura pelo caminho`() {
        val original = fullLibrary()
        val restored = emptyStore()
        restored.restore(fileOf(original))

        restored.readFolder(newFolder, cap1, cap2, cap1Stamp = FileStamp(2_000, 9))

        val workId = original.workId("Sakamoto Days")
        assertEquals(workId, restored.workId("Sakamoto Days"))
        val chapters = restored.chapters(workId)
        assertEquals(original.chapters(workId).map { it.id }, chapters.map { it.id })
        val second = chapters.single { it.name == "Cap. 001" }.id
        assertEquals(halfway, restored.progress(second))
        assertEquals(second, restored.resumeChapter(workId)?.id)
        // The file is this phone's: its content is confirmed on the first opening, and the same hash keeps the reading.
        assertTrue(chapters.single { it.name == "Cap. 001" }.needsContentCheck)
        assertEquals(second, restored.confirmContent(second, "hash-2"))
        assertEquals(halfway, restored.progress(second))
    }

    @Test
    fun `dado um romance dividido restaurado, quando a pasta traz o mesmo sumário, então cada capítulo mantém identificador e leitura`() {
        val original = fullLibrary()
        val restored = emptyStore()
        restored.restore(fileOf(original))

        restored.readFolder(newFolder, cap1, cap2, cap1Stamp = FileStamp(2_000, 9))

        val workId = original.workId("Release That Witch")
        assertEquals(original.chapters(workId).map { it.id to it.tocKey }, restored.chapters(workId).map { it.id to it.tocKey })
        assertEquals(inTheBook, restored.progress(restored.chapterId("Release That Witch", "Capítulo 2")))
    }

    @Test
    fun `dado uma obra restaurada que não está na nova pasta, quando a pasta é lida, então ela fica guardada fora da lista com a leitura`() {
        val original = fullLibrary()
        val restored = emptyStore()
        restored.restore(fileOf(original))

        restored.readFolder(newFolder, gantz)

        assertEquals(listOf("Gantz", "Release That Witch", "Kaiju No. 8"), restored.works().map { it.title })
        val sakamoto = restored.backup("r550", exportedAt).works.single { it.title == "Sakamoto Days" }
        assertFalse(sakamoto.present)
        assertEquals(listOf(read, halfway, ChapterProgress.NEVER_OPENED), sakamoto.chapters.map { it.progress })
    }

    @Test
    fun `dado duas obras locais no mesmo caminho vindas de duas pastas, quando restauro e leio a pasta nova, então as duas ficam guardadas e só a presente reencontra a pasta`() {
        val original = emptyStore()
        original.readFolder(oldFolder, gantz)
        original.saveProgress(original.chapterId("Gantz", "Cap. 001"), read)
        val second = "content://com.android.externalstorage.documents/tree/primary%3ACopy"
        original.keepOnlyFolder(second)
        original.readFolder(second, gantz)
        val present = original.chapterId("Gantz", "Cap. 001")
        original.saveProgress(present, halfway)
        val restored = emptyStore()

        assertTrue(restored.restore(fileOf(original)))
        restored.readFolder(newFolder, gantz)

        assertEquals(present, restored.chapterId("Gantz", "Cap. 001"))
        assertEquals(halfway, restored.progress(present))
        val gantzWorks = restored.backup("r550", exportedAt).works.filter { it.title == "Gantz" }
        assertEquals(listOf(true, false), gantzWorks.map { it.present })
        assertEquals(listOf(halfway, read), gantzWorks.map { it.chapters.single().progress })
    }

    @Test
    fun `dado um servidor do Kavita restaurado, então o app o mostra com as bibliotecas e aceita a mesma conta`() {
        val original = fullLibrary()
        val restored = emptyStore()

        restored.restore(fileOf(original))

        assertEquals(original.kavitaServer(), restored.kavitaServer())
        assertTrue(restored.acceptsKavitaAccount(1))
        assertFalse(restored.acceptsKavitaAccount(2))
    }

    @Test
    fun `dado um backup com obra do Kavita de um servidor que não está no arquivo, quando restauro, então recusa e o app fica vazio`() {
        val broken = fileOf(fullLibrary()).copy(servers = emptyList())
        val target = emptyStore()

        assertFailsWith<IllegalArgumentException> { target.restore(broken) }

        assertTrue(target.isEmpty())
    }

    @Test
    fun `dado um backup com uma edição anterior que aponta para um capítulo que não está nele, quando restauro, então recusa e o app fica vazio`() {
        val file = fileOf(fullLibrary())
        val broken = file.copy(
            works = file.works.map { work ->
                work.copy(chapters = work.chapters.map { if (it.replacedBy != null) it.copy(replacedBy = "nowhere") else it })
            },
        )
        val target = emptyStore()

        assertFailsWith<IllegalArgumentException> { target.restore(broken) }

        assertTrue(target.isEmpty())
    }

    @Test
    fun `dado um backup com posição guardada num capítulo de outra obra, quando restauro, então o banco recusa e o app fica vazio`() {
        val file = fileOf(fullLibrary())
        val elsewhere = file.works.first { it.origin == "local" && it.chapters.isNotEmpty() }.chapters.first().id
        val broken = file.copy(
            works = file.works.map { work ->
                work.copy(alternatives = work.alternatives.map { it.copy(chapterId = elsewhere) })
            },
        )
        val target = emptyStore()

        assertFails { target.restore(broken) }

        assertTrue(target.isEmpty())
    }

    @Test
    fun `dado um backup com dois capítulos marcados para reabrir na mesma obra, quando restauro, então recusa`() {
        val file = fileOf(fullLibrary())
        val broken = file.copy(works = file.works.map { work -> work.copy(chapters = work.chapters.map { it.copy(resume = true) }) })

        assertFailsWith<IllegalArgumentException> { emptyStore().restore(broken) }
    }

    /** Restores [text] on an empty app and returns what it read and the backup the restored app makes. */
    private fun restoreFile(text: String): Pair<Backup, Backup> {
        val file = BackupFormat.read(text)
        val restored = emptyStore()
        assertTrue(restored.restore(file))
        return file to restored.backup(file.appVersion, file.exportedAt)
    }

    @Test
    fun `dado um arquivo do formato 1, quando restauro, então o app volta com a obra, a leitura e onde ela reabre`() {
        val (file, restored) = restoreFile(FORMAT_1)

        assertEquals(file, restored)
        assertEquals("chapter-1", restored.works.single().chapters.single { it.resume }.id)
    }

    @Test
    fun `dado um arquivo do formato 2 com servidor e obra do Kavita, quando restauro, então acordo, fila e posição guardada voltam`() {
        val (file, restored) = restoreFile(FORMAT_2)

        assertEquals(file, restored)
        assertEquals(listOf(2, 5), restored.servers.single().libraries)
    }

    @Test
    fun `dado um arquivo do formato 3 com o lugar num livro, quando restauro, então CFI e fração voltam`() {
        val (file, restored) = restoreFile(FORMAT_3)

        assertEquals(file, restored)
        assertIs<TextPosition>(restored.works.single().chapters.single().progress.position)
    }

    // What the app does with a file the person picks: read it, check it, then restore it.

    private fun stream(text: String): () -> InputStream = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }

    @Test
    fun `dado o arquivo de um backup, quando o app abre, então ele fica pronto para confirmar com o backup lido`() {
        val text = BackupFormat.write(fullLibrary().backup("r550", exportedAt))

        val opening = BackupRestore(emptyStore()).open(stream(text))

        assertEquals(RestoreOpening.Ready(BackupFormat.read(text)), opening)
    }

    @Test
    fun `dado um arquivo que não é um backup, quando o app abre, então recusa como ilegível`() {
        val opening = BackupRestore(emptyStore()).open(stream("Capítulo 1 de Dom Casmurro"))

        assertIs<RestoreResult.Unreadable>(assertIs<RestoreOpening.Refused>(opening).result)
    }

    @Test
    fun `dado um arquivo maior que o teto, quando o app abre, então recusa como ilegível sem ler tudo`() {
        var given = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                given++
                return ' '.code
            }
        }

        val opening = BackupRestore(emptyStore()).open { endless }

        assertIs<RestoreResult.Unreadable>(assertIs<RestoreOpening.Refused>(opening).result)
        assertTrue(given <= BackupRestore.MAX_BYTES + 64 * 1024, "read $given bytes")
    }

    @Test
    fun `dado um arquivo que não se deixa ler, quando o app abre, então diz que não conseguiu ler`() {
        val opening = BackupRestore(emptyStore()).open { throw IOException("The provider is gone") }

        assertIs<RestoreResult.ReadFailed>(assertIs<RestoreOpening.Refused>(opening).result)
    }

    @Test
    fun `dado um app com uma obra, quando o app abre um backup, então recusa antes de ler o arquivo`() {
        val target = emptyStore()
        target.readFolder(newFolder, gantz)
        var opened = false

        val opening = BackupRestore(target).open {
            opened = true
            ByteArrayInputStream(ByteArray(0))
        }

        assertEquals(RestoreOpening.Refused(RestoreResult.NotEmpty), opening)
        assertFalse(opened)
    }

    @Test
    fun `dado um backup lido e confirmado, quando o app restaura, então o resultado diz o endereço do Kavita para a chave`() {
        val file = fileOf(fullLibrary())
        val target = emptyStore()

        assertEquals(RestoreResult.Restored("http://kavita.example:5000"), BackupRestore(target).apply(file))
        assertFalse(target.isEmpty())
    }

    @Test
    fun `dado um backup que o banco recusa, quando o app restaura, então o resultado diz que falhou e o app fica vazio`() {
        val broken = fileOf(fullLibrary()).copy(servers = emptyList())
        val target = emptyStore()

        assertIs<RestoreResult.Failed>(BackupRestore(target).apply(broken))
        assertTrue(target.isEmpty())
    }

    // Found by the external review of 18/11/2026.

    @Test
    fun `dado um romance dividido restaurado, antes da primeira leitura da pasta nova, então ela já acha os capítulos guardados do arquivo`() {
        val restored = emptyStore()
        restored.restore(fileOf(fullLibrary()))

        // The reading of the folder asks this before importing: a file it cannot open keeps these chapters.
        val stored = restored.splitFiles(newFolder)[novel]

        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml"), stored?.chapters?.map { it.key })
    }

    @Test
    fun `dado um capítulo lido restaurado, antes de qualquer leitura da pasta ou do Kavita, então ele já espera a conferência do conteúdo`() {
        val original = fullLibrary()
        val restored = emptyStore()

        restored.restore(fileOf(original))

        // Cap. 001 has a hash, which alone would say its content is known: only the restore's mark makes it wait.
        assertTrue(restored.chapters(original.workId("Sakamoto Days")).single { it.name == "Cap. 001" }.needsContentCheck)
        // A Kavita chapter joins a sync only after its content is confirmed here (choice 7).
        assertEquals(emptyList(), restored.syncItems(checkNotNull(restored.kavitaServer()).id))
    }

    /** Backups this app could not have written, each of which must be refused before writing. */
    private fun malformed(): Map<String, Backup> {
        val file = fileOf(fullLibrary())
        fun Backup.changingWork(title: String, change: (BackupWork) -> BackupWork) =
            copy(works = works.map { if (it.title == title) change(it) else it })
        val sakamoto = file.works.single { it.title == "Sakamoto Days" }
        val (earlier, current) = sakamoto.chapters.filter { it.name == "Cap. 001" }
        return mapOf(
            "edição que se substitui" to file.changingWork("Sakamoto Days") { work ->
                work.copy(chapters = work.chapters.map { if (it.id == current.id) it.copy(replacedBy = earlier.id) else it })
            },
            "edição anterior presente" to file.changingWork("Sakamoto Days") { work ->
                work.copy(chapters = work.chapters.map { if (it.id == earlier.id) it.copy(present = true) else it })
            },
            "obra do Kavita sem biblioteca" to file.changingWork("Kaiju No. 8") { it.copy(remoteLibraryId = null) },
            "obra do Kavita sem série" to file.changingWork("Kaiju No. 8") { it.copy(path = "Kaiju No. 8") },
            "capítulo do Kavita sem volume" to file.changingWork("Kaiju No. 8") { work ->
                work.copy(chapters = work.chapters.map { it.copy(remoteVolumeId = null) })
            },
            "capítulo do Kavita sem número" to file.changingWork("Kaiju No. 8") { work ->
                work.copy(chapters = work.chapters.map { it.copy(path = "Cap. 1") })
            },
            "duas posições pelo mesmo motivo" to file.changingWork("Kaiju No. 8") { work ->
                work.copy(alternatives = work.alternatives + work.alternatives)
            },
        )
    }

    @Test
    fun `dado backups que este app não poderia ter gravado, quando restauro cada um, então todos são recusados e o app fica vazio`() {
        for ((case, backup) in malformed()) {
            val target = emptyStore()

            assertFailsWith<IllegalArgumentException>(case) { target.restore(backup) }

            assertTrue(target.isEmpty(), case)
        }
    }

    @Test
    fun `dado um arquivo legível que este app não poderia ter gravado, quando o app abre, então recusa como ilegível antes da confirmação`() {
        val broken = fileOf(fullLibrary()).copy(servers = emptyList())

        val opening = BackupRestore(emptyStore()).open(stream(BackupFormat.write(broken)))

        assertIs<RestoreResult.Unreadable>(assertIs<RestoreOpening.Refused>(opening).result)
    }

    @Test
    fun `dado um provedor de arquivos que falha com erro de estado ou de argumento, quando o app abre, então diz que não conseguiu ler`() {
        val provider = listOf<() -> InputStream>(
            { throw IllegalStateException("The provider closed") },
            { throw IllegalArgumentException("Unknown document") },
        )

        for (input in provider) {
            assertIs<RestoreResult.ReadFailed>(assertIs<RestoreOpening.Refused>(BackupRestore(emptyStore()).open(input)).result)
        }
    }

    private companion object {
        // Format 1 as the app wrote it from 28/10/2026.
        val FORMAT_1 = """
            {
                "formatVersion": 1, "appVersion": "r98", "exportedAt": "2026-10-28T21:48:00Z",
                "works": [ {
                    "id": "work-1", "versionId": "version-1", "origin": "local", "path": "Comics/Sakamoto Days",
                    "title": "Sakamoto Days", "alternativeTitle": null, "type": "COMICS", "subtype": null, "present": true,
                    "chapters": [ {
                        "id": "chapter-1", "replacedBy": null, "path": "Comics/Sakamoto Days/Capítulo 1.cbz", "name": "Capítulo 1",
                        "kind": "CHAPTER", "number": 1.0, "format": "ARCHIVE", "contentHash": "ab12", "present": true, "resume": true,
                        "readMark": null,
                        "position": { "page": 17, "totalPages": 38, "at": "2026-10-27T23:40:51Z", "device": "phone-a" },
                        "completeReads": 0
                    } ]
                } ]
            }
        """.trimIndent()

        // Format 2 as the app wrote it from 02/11/2026: servers and the Kavita fields.
        val FORMAT_2 = """
            {
                "formatVersion": 2, "appVersion": "r402", "exportedAt": "2026-11-08T23:00:00Z",
                "servers": [ { "id": "server-1", "address": "http://kavita.example:5000", "lastVersion": "0.9.1.4", "accountId": 1, "libraries": [2, 5] } ],
                "works": [ {
                    "id": "work-12", "versionId": "version-12", "origin": "kavita", "path": "12", "title": "Kaiju No. 8",
                    "alternativeTitle": null, "type": "COMICS", "subtype": null, "present": true,
                    "chapters": [ {
                        "id": "chapter-91", "replacedBy": null, "path": "91", "name": "91", "kind": "CHAPTER", "number": 91.0,
                        "format": "ARCHIVE", "contentHash": null, "present": true, "resume": true,
                        "readMark": { "at": "2026-10-25T22:14:03Z", "source": "READ_TO_END" },
                        "position": { "page": 42, "totalPages": 42, "at": "2026-10-25T22:14:03Z", "device": "phone-a" },
                        "completeReads": 1, "pageCount": 42, "remoteFile": "Vol. 1.cbz", "remoteVolumeId": 7,
                        "agreement": { "page": 42, "bookScrollId": null, "serverAt": "2026-10-25T14:10:00", "ownReread": false, "localCfi": null },
                        "outbox": { "page": 17, "bookScrollId": null, "localCfi": null, "state": "TO_SEND", "queuedAt": "2026-10-27T23:40:51Z" }
                    } ],
                    "source": "server-1", "remoteLibraryId": 2,
                    "alternatives": [ { "reason": "CONFLICT", "chapterId": "chapter-91", "page": 3, "total": 42, "at": "2026-10-25T22:14:03Z", "device": "kavita" } ]
                } ]
            }
        """.trimIndent()

        // Format 3 as the app wrote it from piece 22 of step 4: a book's place.
        val FORMAT_3 = """
            {
                "formatVersion": 3, "appVersion": "r454", "exportedAt": "2026-11-18T10:00:00Z", "servers": [],
                "works": [ {
                    "id": "work-dom", "versionId": "version-dom", "origin": "local", "path": "Books/Dom Casmurro.epub",
                    "title": "Dom Casmurro", "alternativeTitle": null, "type": "BOOKS", "subtype": null, "present": true,
                    "chapters": [ {
                        "id": "chapter-dom", "replacedBy": null, "path": "Books/Dom Casmurro.epub", "name": "Dom Casmurro",
                        "kind": "VOLUME", "number": null, "format": "EPUB", "contentHash": "cd34", "present": true, "resume": true,
                        "readMark": null, "position": null, "completeReads": 0, "pageCount": null, "remoteFile": null,
                        "remoteVolumeId": null, "agreement": null, "outbox": null,
                        "textPosition": { "section": 2, "sections": 9, "cfi": "epubcfi(/6/6!/4/2)", "fraction": 2500, "at": "2026-11-17T22:00:00Z", "device": "phone-a" }
                    } ],
                    "source": null, "remoteLibraryId": null, "alternatives": []
                } ]
            }
        """.trimIndent()
    }
}
