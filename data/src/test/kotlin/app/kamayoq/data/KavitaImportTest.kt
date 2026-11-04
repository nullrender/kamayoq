package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KavitaImportTest {
    private val server = "server-1"
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)
    private val reading = ChapterProgress(
        readMark = null,
        position = StripPosition(7, 20, Instant.parse("2026-10-25T14:10:00Z"), "phone-a"),
        completeReads = 0,
    )

    @AfterTest
    fun close() = driver.close()

    private fun chapter(
        id: Int,
        number: Double? = id.toDouble(),
        pages: Int = 20,
        file: String = "Cap. $id.cbz",
        stamp: String = "$pages|100",
    ) = ImportedChapter(
        path = id.toString(),
        name = "Cap. $id",
        kind = UnitKind.CHAPTER,
        number = number,
        format = ChapterFormat.ARCHIVE,
        stamp = stamp,
        pageCount = pages,
        remoteFile = file,
        remoteVolumeId = 7,
    )

    private fun work(id: Int, title: String, vararg chapters: ImportedChapter, library: Int = 2) =
        ImportedWork(id.toString(), title, WorkType.COMICS, null, library, chapters.toList())

    private fun work(title: String) = store.works().single { it.title == title }

    private fun chapterIds(title: String) = store.chapters(work(title).id).map { it.id }

    @Test
    fun `dado a biblioteca marcada, quando importo, então a Biblioteca tem as obras com os capítulos e as páginas certos`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), chapter(11)), work(2, "Gantz", chapter(12))))

        assertEquals(listOf("Sakamoto Days", "Gantz"), store.works().map { it.title })
        assertEquals(listOf("Cap. 10", "Cap. 11"), store.chapters(work("Sakamoto Days").id).map { it.name })
        assertEquals(setOf(LibraryStore.KAVITA), store.works().map { it.origin }.toSet())
        val backup = store.backup("r1", Instant.EPOCH).works.first()
        assertEquals(listOf(20, 20), backup.chapters.map { it.pageCount })
        assertEquals(listOf("Cap. 10.cbz", "Cap. 11.cbz"), backup.chapters.map { it.remoteFile })
        assertEquals(listOf(7, 7), backup.chapters.map { it.remoteVolumeId })
        assertEquals(server to 2, backup.source to backup.remoteLibraryId)
    }

    @Test
    fun `dado uma importação feita, quando importo de novo sem mudança, então nenhum id local muda`() {
        val works = listOf(work(1, "Sakamoto Days", chapter(10), chapter(11)))
        store.importKavita(server, works)
        val workId = work("Sakamoto Days").id
        val chapters = chapterIds("Sakamoto Days")

        store.importKavita(server, works)

        assertEquals(workId, work("Sakamoto Days").id)
        assertEquals(chapters, chapterIds("Sakamoto Days"))
    }

    @Test
    fun `dado uma série com progresso que some do Kavita, quando reimporto, então a obra fica ausente e o progresso continua`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val workId = work("Sakamoto Days").id
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)

        store.importKavita(server, emptyList())

        assertEquals(emptyList(), store.works())
        assertEquals(reading, store.progress(chapterId))
        assertEquals(workId, store.backup("r1", Instant.EPOCH).works.single().id)
    }

    @Test
    fun `dado a pasta local relida, quando marco ausentes, então as obras do Kavita não somem`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val folder = "content://pasta"

        store.keepOnlyFolder(folder)
        store.importLocal(
            folder,
            FolderConvention.read(listOf("Comics/Gantz/Cap. 001.cbz")),
            mapOf("Comics/Gantz/Cap. 001.cbz" to FileStamp(1, 1)),
        )

        assertEquals(listOf("Gantz", "Sakamoto Days"), store.works().map { it.title })
    }

    @Test
    fun `dado um capítulo que volta com outro id e candidato único, quando importo, então ele religa com o progresso`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), chapter(11))))
        val chapterId = chapterIds("Sakamoto Days").last()
        store.saveProgress(chapterId, reading)

        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), chapter(99, number = 11.0, file = "Cap. 11.cbz"))))

        assertEquals(chapterId, chapterIds("Sakamoto Days").last())
        assertEquals(reading, store.progress(chapterId))
        assertEquals("99", store.chapters(work("Sakamoto Days").id).last().path)
    }

    @Test
    fun `dado um capítulo religado, quando importo, então o acordo volta ao começo e o conteúdo será conferido`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(11))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.confirmContent(chapterId, "hash")
        store.saveProgress(chapterId, reading)
        driver.execute(null, "INSERT INTO progress_agreement VALUES ('$chapterId', 7, NULL, 'x', 0, NULL)", 0)

        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(99, number = 11.0))))

        // A chapter back with another id has no record in the server (KavitaFactsTest).
        assertEquals(0, store.backup("r1", Instant.EPOCH).works.single().chapters.single().agreement?.page)
        assertTrue(store.chapters(work("Sakamoto Days").id).single().needsContentCheck)
    }

    @Test
    fun `dado dois capítulos iguais que voltam com outros ids e os mesmos nomes, quando importo, então o nome desempata`() {
        val a = chapter(10, number = null, file = "A.epub")
        val b = chapter(11, number = null, file = "B.epub")
        store.importKavita(server, listOf(work(1, "Same Title", a, b)))
        val (idOfA, idOfB) = chapterIds("Same Title")
        store.saveProgress(idOfB, reading)

        store.importKavita(server, listOf(work(1, "Same Title", chapter(21, number = null, file = "B.epub"), chapter(20, number = null, file = "A.epub"))))

        assertEquals(listOf(idOfB, idOfA), chapterIds("Same Title"))
    }

    @Test
    fun `dado um candidato ambíguo, quando importo, então nada religa e o sumido fica guardado`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10, number = 1.0, file = "x.cbz"), chapter(11, number = 1.0, file = "x.cbz"))))
        val before = chapterIds("Sakamoto Days")
        store.saveProgress(before.first(), reading)

        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(99, number = 1.0, file = "x.cbz"))))

        assertNotEquals(before.first(), chapterIds("Sakamoto Days").single())
        assertEquals(reading, store.progress(before.first()))
    }

    @Test
    fun `dado uma série inteira que volta com outro id, quando importo, então a obra e o progresso continuam`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val workId = work("Sakamoto Days").id
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)
        store.importKavita(server, emptyList())

        store.importKavita(server, listOf(work(50, "Sakamoto Days", chapter(60, number = 10.0))))

        assertEquals(workId, work("Sakamoto Days").id)
        assertEquals(listOf(chapterId), chapterIds("Sakamoto Days"))
        assertEquals(reading, store.progress(chapterId))
    }

    @Test
    fun `dado o mesmo título em outra biblioteca, quando importo, então é outra obra`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), library = 2)))
        val workId = work("Sakamoto Days").id
        store.importKavita(server, emptyList())

        store.importKavita(server, listOf(work(50, "Sakamoto Days", chapter(60), library = 3)))

        assertNotEquals(workId, work("Sakamoto Days").id)
    }

    @Test
    fun `dado um arquivo trocado com progresso, quando importo, então o conteúdo será conferido ao abrir`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10, stamp = "20|100"))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.confirmContent(chapterId, "hash")
        store.saveProgress(chapterId, reading)

        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10, stamp = "20|999"))))

        assertTrue(store.chapters(work("Sakamoto Days").id).single().needsContentCheck)
    }

    @Test
    fun `dado uma biblioteca desmarcada, quando escondo, então as obras dela saem da lista sem rede`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), library = 2), work(2, "Gantz", chapter(11), library = 3)))

        store.keepOnlyLibraries(server, listOf(3))

        assertEquals(listOf("Gantz"), store.works().map { it.title })
    }

    @Test
    fun `dado uma posição vinda do Kavita num capítulo nunca aberto, quando abro, então ela fica no mesmo capítulo`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)

        assertEquals(chapterId, store.confirmContent(chapterId, "hash"))
        assertEquals(reading, store.progress(chapterId))
    }

    @Test
    fun `dado o Kavita mais adiante, quando adoto, então a posição dele vale e a anterior fica guardada`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)
        val at = Instant.parse("2026-10-26T10:00:00Z")

        store.adoptRemote(chapterId, page = 10, pageCount = 20, serverAt = "x", at = at, reason = LibraryStore.PREVIOUS)

        val saved = store.progress(chapterId)
        assertEquals(10 to "kavita", saved.position?.page to saved.position?.device)
        assertNull(saved.readMark)
        assertEquals(listOf(7), store.alternatives(work("Sakamoto Days").id).map { it.page })
        assertEquals(10, store.syncItem(chapterId)?.agreedPage)
    }

    @Test
    fun `dado o fim vindo do Kavita, quando adoto, então o capítulo fica lido, sem contar leitura completa aqui`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()

        store.adoptRemote(chapterId, page = 20, pageCount = 20, serverAt = "x", at = Instant.EPOCH, reason = LibraryStore.PREVIOUS)

        val saved = store.progress(chapterId)
        assertEquals(ReadMarkSource.KAVITA, saved.readMark?.source)
        assertEquals(0, saved.completeReads)
    }

    @Test
    fun `dado um capítulo lido aqui e uma posição parcial do Kavita, quando adoto, então a marca de lido continua`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        val mark = ReadMark(Instant.EPOCH, ReadMarkSource.READ_TO_END)
        store.saveProgress(chapterId, reading.copy(readMark = mark))

        store.adoptRemote(chapterId, page = 3, pageCount = 20, serverAt = "x", at = Instant.EPOCH, reason = LibraryStore.PREVIOUS)

        assertEquals(mark, store.progress(chapterId).readMark)
    }

    @Test
    fun `dado um conflito resolvido a favor daqui, quando guardo, então a do Kavita fica como outra posição e o envio fica livre`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)

        store.keepLocal(chapterId, page = 12, pageCount = 20, serverAt = "x", at = Instant.EPOCH)

        assertEquals(listOf(LibraryStore.CONFLICT to 12), store.alternatives(work("Sakamoto Days").id).map { it.reason to it.page })
        assertEquals(12, store.syncItem(chapterId)?.agreedPage)
        assertEquals(reading, store.progress(chapterId))
    }

    @Test
    fun `dado a posição anterior guardada, quando volto a ela, então ela vale de novo e sai da lista`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)
        store.adoptRemote(chapterId, page = 10, pageCount = 20, serverAt = "x", at = Instant.EPOCH, reason = LibraryStore.PREVIOUS)
        val workId = work("Sakamoto Days").id

        store.useAlternative(workId, store.alternatives(workId).single())

        assertEquals(7, store.progress(chapterId).position?.page)
        assertEquals(emptyList(), store.alternatives(workId))
    }

    @Test
    fun `dado um capítulo de texto, então ele fica fora da sincronização`() {
        val text = chapter(10).copy(format = ChapterFormat.EPUB)
        store.importKavita(server, listOf(work(1, "Livro", text)))

        assertEquals(emptyList(), store.syncItems(server))
    }

    @Test
    fun `dado conteúdo diferente num capítulo do Kavita, quando a edição nova nasce, então ela começa sem acordo e a fila antiga sai`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.confirmContent(chapterId, "hash-A")
        store.saveProgress(chapterId, reading)
        store.agree(chapterId, 7, "x", ownReread = false)
        store.markSending(chapterId, 9, Instant.EPOCH)

        val edition = checkNotNull(store.syncItem(store.confirmContent(chapterId, "hash-B")))

        assertNull(edition.agreedPage)
        assertTrue(edition.isEdition)
        assertNull(edition.outboxState)
        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(edition.chapterId))
    }

    @Test
    fun `dado um capítulo com conteúdo em dúvida, então ele sai da sincronização até o resumo confirmar`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10, stamp = "20|100"))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.confirmContent(chapterId, "hash")
        assertEquals(1, store.syncItems(server).size)

        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10, stamp = "20|999"))))

        assertEquals(emptyList(), store.syncItems(server))
        assertNull(store.syncItem(chapterId))
        store.confirmContent(chapterId, "hash")
        assertEquals(1, store.syncItems(server).size)
    }

    @Test
    fun `dado a retomada em outro capítulo adotado, quando assento, então a posição guardada é a de antes da adoção`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), chapter(11))))
        val (five, six) = chapterIds("Sakamoto Days")
        store.saveProgress(five, reading)
        val before = store.resumeOf(five)
        store.adoptRemote(five, 20, 20, "x", Instant.EPOCH, LibraryStore.PREVIOUS, moveResume = false)
        store.adoptRemote(six, 5, 20, "x", Instant.EPOCH, LibraryStore.PREVIOUS, moveResume = false)

        store.settleResume(six, localMoved = false, before = before)

        assertEquals(six, store.resumeChapter(work("Sakamoto Days").id)?.id)
        assertEquals(listOf(five to 7), store.alternatives(work("Sakamoto Days").id).map { it.chapterId to it.page })
    }

    @Test
    fun `dado a retomada adotada do Kavita e outro capítulo lido aqui no meio, quando assento, então ela fica como outra posição`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10), chapter(11))))
        val (first, second) = chapterIds("Sakamoto Days")
        store.saveProgress(first, reading)
        val before = store.resumeOf(first)
        store.saveProgress(second, reading)
        store.adoptRemote(first, 15, 20, "x", Instant.EPOCH, LibraryStore.PREVIOUS, moveResume = false)

        store.settleResume(first, localMoved = true, before = before)

        assertEquals(second, store.resumeChapter(work("Sakamoto Days").id)?.id)
        assertEquals(listOf(first to 15), store.alternatives(work("Sakamoto Days").id).filter { it.reason == LibraryStore.CONFLICT }.map { it.chapterId to it.page })
    }

    @Test
    fun `dado uma posição guardada de capítulo substituído, então ela sai da lista da Obra`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)
        store.adoptRemote(chapterId, 10, 20, "x", Instant.EPOCH, LibraryStore.PREVIOUS)
        val workId = work("Sakamoto Days").id
        assertEquals(1, store.alternatives(workId).size)

        store.confirmContent(chapterId, "hash-A")
        store.confirmContent(chapterId, "hash-B")

        assertEquals(emptyList(), store.alternatives(workId))
    }

    @Test
    fun `dado uma posição do Kavita no fim, quando a uso, então o capítulo fica lido`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, reading)
        store.keepLocal(chapterId, page = 20, pageCount = 20, serverAt = "x", at = Instant.EPOCH)
        val workId = work("Sakamoto Days").id

        store.useAlternative(workId, store.alternatives(workId).single())

        assertEquals(ReadMarkSource.KAVITA, store.progress(chapterId).readMark?.source)
    }

    @Test
    fun `dado uma posição no começo do capítulo, quando adoto, então não há anterior a guardar`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        val chapterId = chapterIds("Sakamoto Days").single()
        store.saveProgress(chapterId, ChapterProgress(null, StripPosition(0, 20, Instant.EPOCH, "phone-a"), 0))

        store.adoptRemote(chapterId, 10, 20, "x", Instant.EPOCH, LibraryStore.PREVIOUS)

        assertEquals(emptyList(), store.alternatives(work("Sakamoto Days").id))
    }

    @Test
    fun `dado uma obra que sumiu do Kavita, então ela sai da sincronização`() {
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))

        store.importKavita(server, emptyList())

        assertEquals(emptyList(), store.syncItems(server))
    }

    @Test
    fun `dado bibliotecas que mudaram durante a leitura, quando a importação antiga termina, então ela não escreve nada`() {
        store.linkKavita("http://a.example", "0.9.1.4", accountId = 1)
        val id = checkNotNull(store.kavitaServer()).id
        store.markLibraries(id, listOf(2, 3))
        store.markLibraries(id, listOf(3))

        store.importKavita(id, listOf(work(1, "Sakamoto Days", chapter(10), library = 2)), markedAtStart = listOf(2, 3))

        assertEquals(emptyList(), store.works())
    }

    @Test
    fun `dado a primeira conexão, quando ligo o servidor, então ele fica guardado sem biblioteca marcada`() {
        store.linkKavita("http://a.example", "0.9.1.4", accountId = 1)

        val saved = store.kavitaServer()
        assertEquals("http://a.example" to emptyList(), saved?.address to saved?.marked)
    }

    @Test
    fun `dado um servidor que mudou de endereço, quando reconecto com a mesma conta, então as obras são as mesmas`() {
        store.linkKavita("http://a.example", "0.9.1.4", accountId = 1)
        val id = checkNotNull(store.kavitaServer()).id
        store.importKavita(id, listOf(work(1, "Sakamoto Days", chapter(10))))
        val workId = work("Sakamoto Days").id

        store.linkKavita("http://b.example", "0.9.1.4", accountId = 1)
        store.importKavita(checkNotNull(store.kavitaServer()).id, listOf(work(1, "Sakamoto Days", chapter(10))))

        assertEquals("http://b.example", store.kavitaServer()?.address)
        assertEquals(workId, work("Sakamoto Days").id)
    }

    @Test
    fun `dado um servidor guardado, então só a mesma conta é aceita`() {
        store.linkKavita("http://a.example", "0.9.1.4", accountId = 1)

        assertTrue(store.acceptsKavitaAccount(1))
        assertFalse(store.acceptsKavitaAccount(2))
    }

    @Test
    fun `dado bibliotecas marcadas e depois desmarcadas, então guarda só as atuais e esconde as obras da outra`() {
        store.linkKavita("http://a.example", "0.9.1.4", accountId = 1)
        val id = checkNotNull(store.kavitaServer()).id
        store.markLibraries(id, listOf(2, 3))
        store.importKavita(id, listOf(work(1, "Sakamoto Days", chapter(10), library = 2), work(2, "Gantz", chapter(11), library = 3)))

        store.markLibraries(id, listOf(3))

        assertEquals(listOf(3), store.kavitaServer()?.marked)
        assertEquals(listOf("Gantz"), store.works().map { it.title })
    }

    @Test
    fun `dado obras locais e do Kavita, então as locais vêm antes`() {
        val folder = "content://pasta"
        store.importKavita(server, listOf(work(1, "Sakamoto Days", chapter(10))))
        store.importLocal(
            folder,
            FolderConvention.read(listOf("Comics/Gantz/Cap. 001.cbz")),
            mapOf("Comics/Gantz/Cap. 001.cbz" to FileStamp(1, 1)),
        )

        assertEquals(listOf("Gantz", "Sakamoto Days"), store.works().map { it.title })
    }
}
