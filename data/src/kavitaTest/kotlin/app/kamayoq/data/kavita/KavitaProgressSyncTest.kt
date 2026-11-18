package app.kamayoq.data.kavita

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.progress.strip
import app.kamayoq.data.LibraryStore
import app.kamayoq.core.kavita.RemoteProgress
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.SyncItem
import java.io.IOException
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Each test takes a chapter of its own: the server keeps what the others wrote. */
class KavitaProgressSyncTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)
    private val client = KavitaClient()
    private val sync = KavitaProgressSync(client, store)
    private val key = KavitaServer.authKey
    private val address = KavitaServer.url
    private val remoteWork by lazy { KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Synced Work" } }

    @AfterTest
    fun close() = driver.close()

    private fun chapter(number: Double): StoredChapter {
        store.linkKavita(address, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, listOf(KavitaServer.mutableLibraryId))
        KavitaImport(client, store).run(key)
        val work = store.works().single { it.title == "Synced Work" }
        return store.chapters(work.id).single { it.number == number }
    }

    private fun remote(chapter: StoredChapter) = KavitaServer.chapters(remoteWork.id).single { it.id.toString() == chapter.path }

    private fun remotePage(chapter: StoredChapter) = KavitaServer.progress(remote(chapter)).getValue("pageNum").jsonPrimitive.int

    private fun read(chapter: StoredChapter, page: Int, mark: ReadMark? = null) = store.saveProgress(
        chapter.id,
        ChapterProgress(mark, StripPosition(page, 12, Instant.parse("2026-10-25T14:10:00Z"), "phone-a"), 0),
    )

    private fun item(chapter: StoredChapter) = checkNotNull(store.syncItem(chapter.id))

    // Step 4, milestone C2: a book's place goes as its section and marker. Each test reads a book of
    // its own, of three sections (KavitaFixtures.writeEpub).
    private fun book(title: String): StoredChapter {
        store.linkKavita(address, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, listOf(KavitaServer.bookLibraryId))
        KavitaImport(client, store).run(key)
        return store.chapters(store.works().single { it.title == title }.id).single()
    }

    private fun remoteBook(title: String) = KavitaServer.works(KavitaServer.bookLibraryId).single { it.name == title }

    private fun remoteBookChapter(title: String) = KavitaServer.chapters(remoteBook(title).id).single()

    /** Page and marker as the server has them now. */
    private fun remotePlace(title: String): Pair<Int, String?> {
        val progress = KavitaServer.progress(remoteBookChapter(title))
        val marker = (progress["bookScrollId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return progress.getValue("pageNum").jsonPrimitive.int to marker
    }

    private fun hasRecord(title: String) = KavitaServer.progress(remoteBookChapter(title)).getValue("volumeId").jsonPrimitive.int != 0

    private fun readText(chapter: StoredChapter, section: Int, marker: String?, sections: Int = 3, ended: Boolean = false) = store.saveProgress(
        chapter.id,
        ChapterProgress(
            if (ended) ReadMark(Instant.EPOCH, ReadMarkSource.READ_TO_END) else null,
            TextPosition(section, sections, "epubcfi(/6/${(section + 1) * 2}!/4/2/1:${marker?.length ?: 0})", checkNotNull(BasisPoints.fromFraction(0.5)), Instant.EPOCH, "phone-a"),
            0,
        ),
        marker,
    )

    @Test
    fun `dado a seção 2 lida aqui com marcador, quando sincronizo duas vezes, então o Kavita guarda a página 2 com o marcador e a fila esvazia`() {
        val chapter = book("Read Here Book")
        readText(chapter, 2, "//body/p[2]")

        assertEquals(KavitaSyncOutcome.Settled, sync.chapter(address, key, item(chapter)))
        assertEquals(KavitaSyncOutcome.Settled, sync.chapter(address, key, item(chapter)))

        assertEquals(2 to "//body/p[2]", remotePlace("Read Here Book"))
        assertEquals(2 to "//body/p[2]", item(chapter).agreedPage to item(chapter).agreedMarker)
        assertNull(item(chapter).outboxState)
    }

    @Test
    fun `dado a primeira seção lida aqui e nenhum registro no Kavita, quando sincronizo, então nada é enviado e o lugar fica aqui`() {
        val chapter = book("First Section Book")
        readText(chapter, 0, "//body/p[2]")

        sync.chapter(address, key, item(chapter))

        assertEquals(false, hasRecord("First Section Book"))
        assertEquals(0, (store.progress(chapter.id).position as TextPosition).section)
    }

    @Test
    fun `dado o livro lido até o fim aqui, quando sincronizo, então o Kavita marca o livro lido`() {
        val chapter = book("Ended Book")
        readText(chapter, 2, "//body/p[1]", ended = true)

        sync.chapter(address, key, item(chapter))

        val after = remoteBookChapter("Ended Book")
        assertEquals(after.pages, after.pagesRead)
    }

    @Test
    fun `dado outro aparelho na seção 1 com marcador, quando sincronizo, então adoto a seção 1 e o marcador fica para o leitor`() {
        val chapter = book("Adopted Book")
        KavitaServer.saveProgress(remoteBook("Adopted Book"), remoteBookChapter("Adopted Book"), page = 1, bookScrollId = "//body/p[2]")

        assertEquals(KavitaSyncOutcome.Adopted, sync.chapter(address, key, item(chapter)))

        val position = store.progress(chapter.id).position as TextPosition
        assertEquals(1 to "kavita", position.section to position.device)
        assertEquals("//body/p[2]", store.agreedMarker(chapter.id))
    }

    @Test
    fun `dado o lido do Kavita num EPUB, quando sincronizo, então o livro fica lido aqui e reabre na última seção`() {
        val chapter = book("Read There Book")
        KavitaServer.saveProgress(remoteBook("Read There Book"), remoteBookChapter("Read There Book"), page = 3, bookScrollId = "//body/p[1]")

        sync.chapter(address, key, item(chapter))

        val progress = store.progress(chapter.id)
        assertEquals(ReadMarkSource.KAVITA, progress.readMark?.source)
        assertEquals(2, (progress.position as TextPosition).section)
        assertEquals("//body/p[1]", store.agreedMarker(chapter.id))
    }

    @Test
    fun `dado só o marcador mudado nos dois lados na mesma seção, quando sincronizo, então não é conflito e vai o daqui`() {
        val chapter = book("Marker Book")
        readText(chapter, 1, "//body/p[1]")
        sync.chapter(address, key, item(chapter))
        KavitaServer.saveProgress(remoteBook("Marker Book"), remoteBookChapter("Marker Book"), page = 1, bookScrollId = "//body/p[2]")
        readText(chapter, 1, """id("x")""")

        assertEquals(KavitaSyncOutcome.Settled, sync.chapter(address, key, item(chapter)))

        assertEquals(1 to """id("x")""", remotePlace("Marker Book"))
    }

    @Test
    fun `dado seções diferentes nos dois lados, quando sincronizo, então é conflito e nada é enviado`() {
        val chapter = book("Crossed Book")
        readText(chapter, 1, "//body/p[1]")
        sync.chapter(address, key, item(chapter))
        KavitaServer.saveProgress(remoteBook("Crossed Book"), remoteBookChapter("Crossed Book"), page = 2, bookScrollId = "//body/p[2]")
        readText(chapter, 0, "//body/p[2]")

        val outcome = assertIs<KavitaSyncOutcome.Conflict>(sync.chapter(address, key, item(chapter)))

        assertEquals(2 to "//body/p[2]", outcome.remotePage to outcome.remoteMarker)
        assertEquals(2 to "//body/p[2]", remotePlace("Crossed Book"))
    }

    @Test
    fun `dado um livro que o Kavita conta com outras seções, quando sincronizo, então nada é enviado e o lugar fica aqui`() {
        val chapter = book("Recounted Book")
        readText(chapter, 1, "//body/p[1]", sections = 4)

        sync.chapter(address, key, item(chapter))

        assertEquals(false, hasRecord("Recounted Book"))
        assertEquals(4, (store.progress(chapter.id).position as TextPosition).sections)
    }

    @Test
    fun `dado o Kavita inacessível, quando leio duas vezes, então só o último lugar com o marcador vai quando a rede volta`() {
        val chapter = book("Offline Book")
        readText(chapter, 1, "//body/p[1]")
        sync.afterReading("http://localhost:1", key, chapter.id)
        readText(chapter, 2, "//body/p[2]")
        sync.afterReading("http://localhost:1", key, chapter.id)
        assertEquals("TO_SEND" to "//body/p[2]", item(chapter).outboxState to item(chapter).outboxMarker)

        sync.afterReading(address, key, chapter.id)

        assertEquals(2 to "//body/p[2]", remotePlace("Offline Book"))
        assertNull(item(chapter).outboxState)
    }

    @Test
    fun `dado a página 3 lida aqui, quando sincronizo, então o Kavita guarda 3, o acordo fica em 3 e a fila esvazia`() {
        val chapter = chapter(1.0)
        read(chapter, 3)

        assertEquals(KavitaSyncOutcome.Settled, sync.chapter(address, key, item(chapter)))

        assertEquals(3, remotePage(chapter))
        assertEquals(3, item(chapter).agreedPage)
        assertNull(item(chapter).outboxState)
    }

    @Test
    fun `dado outro aparelho que leu até a página 5, quando sincronizo, então adoto a 5`() {
        val chapter = chapter(2.0)
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 5)

        sync.chapter(address, key, item(chapter))

        assertEquals(5, store.progress(chapter.id).position?.strip()?.page)
    }

    @Test
    fun `dado os dois lados mudados, quando sincronizo, então é conflito e nada é enviado`() {
        val chapter = chapter(3.0)
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 7)
        read(chapter, 2)

        val outcome = sync.chapter(address, key, item(chapter))

        assertEquals(7, assertIs<KavitaSyncOutcome.Conflict>(outcome).remotePage)
        assertEquals(7, remotePage(chapter))
    }

    @Test
    fun `dado o bloco de fim lido aqui, quando sincronizo, então o capítulo fica lido no Kavita`() {
        val chapter = chapter(4.0)
        read(chapter, 12, ReadMark(Instant.EPOCH, ReadMarkSource.READ_TO_END))

        sync.chapter(address, key, item(chapter))

        val after = remote(chapter)
        assertEquals(after.pages, after.pagesRead)
    }

    @Test
    fun `dado o Kavita inacessível, quando salvo duas vezes, então só a última vai quando a rede volta`() {
        val chapter = chapter(5.0)
        read(chapter, 2)
        sync.afterReading("http://localhost:1", key, chapter.id)
        assertEquals("TO_SEND" to 2, item(chapter).outboxState to item(chapter).outboxPage)
        read(chapter, 6)
        sync.afterReading("http://localhost:1", key, chapter.id)
        assertEquals("TO_SEND" to 6, item(chapter).outboxState to item(chapter).outboxPage)

        sync.afterReading(address, key, chapter.id)

        assertEquals(6, remotePage(chapter))
        assertNull(item(chapter).outboxState)
    }

    @Test
    fun `dado um envio sem confirmação que chegou ao Kavita, quando sincronizo, então confirmo sem conflito`() {
        val chapter = chapter(6.0)
        read(chapter, 4)
        store.markSending(chapter.id, 4, Instant.EPOCH)
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 4)
        read(chapter, 9)

        val outcome = sync.chapter(address, key, item(chapter))

        assertEquals(KavitaSyncOutcome.Settled, outcome)
        assertEquals(9, remotePage(chapter))
    }

    @Test
    fun `dado que outro aparelho grava entre a leitura e o envio, quando sincronizo, então a gravação dele se perde`() {
        // The limit D1 of the contract, accepted there: the server has no version of a record to refuse on.
        val chapter = chapter(9.0)
        read(chapter, 3)
        val between = object : KavitaClient() {
            override fun saveProgress(address: String, key: String, item: SyncItem, page: Int, marker: String?) {
                KavitaServer.saveProgress(remoteWork, remote(chapter), page = 11)
                super.saveProgress(address, key, item, page, marker)
            }
        }

        KavitaProgressSync(between, store).chapter(address, key, item(chapter))

        assertEquals(3, remotePage(chapter))
    }

    @Test
    fun `dado a confirmação que falha depois do envio, quando sincronizo de novo, então confirmo sem enviar outra vez`() {
        val chapter = chapter(10.0)
        read(chapter, 6)
        var sends = 0
        var failing = true
        val flaky = object : KavitaClient() {
            override fun saveProgress(address: String, key: String, item: SyncItem, page: Int, marker: String?) {
                sends++
                super.saveProgress(address, key, item, page, marker)
            }

            override fun progress(address: String, key: String, chapterId: Int): RemoteProgress {
                if (sends == 1 && failing) {
                    failing = false
                    throw IOException("The answer was lost")
                }
                return super.progress(address, key, chapterId)
            }
        }
        assertFailsWith<IOException> { KavitaProgressSync(flaky, store).chapter(address, key, item(chapter)) }
        assertEquals("SENT_UNCONFIRMED", item(chapter).outboxState)

        KavitaProgressSync(flaky, store).chapter(address, key, item(chapter))

        assertEquals(1, sends)
        assertEquals(6, item(chapter).agreedPage)
        assertNull(item(chapter).outboxState)
    }

    @Test
    fun `dado leitura aqui num capítulo e avanço no Kavita em outro, quando sincronizo tudo, então a retomada fica e o outro vira outra posição`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Crossed Work" }
        val here = store.chapters(work.id).first()
        val there = store.chapters(work.id).last()
        val remoteCrossed = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Crossed Work" }
        read(here, 3)
        val thereRemote = KavitaServer.chapters(remoteCrossed.id).single { it.id.toString() == there.path }
        KavitaServer.saveProgress(remoteCrossed, thereRemote, page = 8)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(here.id, store.resumeChapter(work.id)?.id)
        assertEquals(listOf(LibraryStore.CONFLICT to there.id), store.alternatives(work.id).map { it.reason to it.chapterId })
    }

    @Test
    fun `dado avanço no Kavita em outro capítulo e nada novo aqui, quando sincronizo tudo, então a retomada muda e a anterior fica guardada`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Moved Work" }
        val first = store.chapters(work.id).first()
        val last = store.chapters(work.id).last()
        val remoteMoved = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Moved Work" }
        read(first, 4)
        sync.all(address, key, KavitaImport(client, store).run(key))
        val lastRemote = KavitaServer.chapters(remoteMoved.id).single { it.id.toString() == last.path }
        KavitaServer.saveProgress(remoteMoved, lastRemote, page = 8)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(last.id, store.resumeChapter(work.id)?.id)
        assertEquals(listOf(first.id), store.alternatives(work.id).filter { it.reason == LibraryStore.PREVIOUS }.map { it.chapterId })
    }

    @Test
    fun `dado o capítulo aberto no leitor, quando sincronizo tudo, então ele fica de fora e nada muda por baixo do leitor`() {
        val chapter = chapter(11.0)
        read(chapter, 3)
        sync.beforeOpening(address, key, chapter.id)
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 9)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(3, store.progress(chapter.id).position?.strip()?.page)
        assertEquals(9, remotePage(chapter))
    }

    @Test
    fun `dado um envio sem confirmação e tudo igual de novo, quando sincronizo, então a fila esvazia e a leitura de outro aparelho não se perde`() {
        val chapter = chapter(12.0)
        read(chapter, 5)
        sync.chapter(address, key, item(chapter))
        store.markSending(chapter.id, 8, Instant.EPOCH)

        sync.chapter(address, key, item(chapter))
        assertNull(item(chapter).outboxState)

        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 8)
        sync.chapter(address, key, item(chapter))
        assertEquals(8, store.progress(chapter.id).position?.strip()?.page)
    }

    @Test
    fun `dado o Kavita adiante e o conteúdo trocado, quando a edição nova sincroniza, então ela não herda a posição nem a marca`() {
        val chapter = chapter(13.0)
        read(chapter, 3)
        sync.chapter(address, key, item(chapter))
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 12)
        store.confirmContent(chapter.id, "hash-A")
        val edition = store.confirmContent(chapter.id, "hash-B")

        sync.chapter(address, key, checkNotNull(store.syncItem(edition)))

        assertEquals(ChapterProgress.NEVER_OPENED, store.progress(edition))
        assertEquals(12, remotePage(chapter))
    }

    @Test
    fun `dado a edição nova lida aqui, quando sincronizo, então a página dela vai ao Kavita`() {
        val chapter = chapter(14.0)
        read(chapter, 3)
        sync.chapter(address, key, item(chapter))
        store.confirmContent(chapter.id, "hash-A")
        val edition = store.confirmContent(chapter.id, "hash-B")
        sync.chapter(address, key, checkNotNull(store.syncItem(edition)))
        store.saveProgress(edition, ChapterProgress(null, StripPosition(5, 12, Instant.EPOCH, "phone-a"), 0))

        sync.chapter(address, key, checkNotNull(store.syncItem(edition)))

        assertEquals(5, remotePage(chapter))
    }

    @Test
    fun `dado o leitor abrindo um capítulo no meio do lote e lendo, quando o lote chega nele, então nada muda por baixo do leitor`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Opened Work" }
        val (first, second) = store.chapters(work.id)
        val remoteOne = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Opened Work" }
        val remoteChapters = KavitaServer.chapters(remoteOne.id)
        KavitaServer.saveProgress(remoteOne, remoteChapters.single { it.id.toString() == first.path }, page = 3)
        KavitaServer.saveProgress(remoteOne, remoteChapters.single { it.id.toString() == second.path }, page = 6)
        racer = KavitaProgressSync(client, store, beforeVisit = { item ->
            if (item.chapterId == second.id) {
                racing().beforeOpening(address, key, second.id)
                read(second, 8)
            }
        })

        racing().all(address, key, KavitaImport(client, store).run(key))

        assertEquals(3, store.progress(first.id).position?.strip()?.page)
        assertEquals(8, store.progress(second.id).position?.strip()?.page)
        assertEquals(6, KavitaServer.progress(remoteChapters.single { it.id.toString() == second.path }).getValue("pageNum").jsonPrimitive.int)
    }

    @Test
    fun `dado um capítulo aberto no leitor e avanço no Kavita noutro capítulo da obra, quando o lote assenta, então a retomada não muda sem perguntar`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Raced Work" }
        val (first, second) = store.chapters(work.id)
        val remoteOne = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Raced Work" }
        read(second, 2)
        sync.beforeOpening(address, key, second.id)
        KavitaServer.saveProgress(remoteOne, KavitaServer.chapters(remoteOne.id).single { it.id.toString() == first.path }, page = 4)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(second.id, store.resumeChapter(work.id)?.id)
        assertEquals(listOf(first.id), store.alternatives(work.id).filter { it.reason == LibraryStore.CONFLICT }.map { it.chapterId })
    }

    @Test
    fun `dado um capítulo aberto, lido e fechado durante o lote, quando o lote assenta, então a retomada não muda sem perguntar`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Closed Work" }
        val (first, second) = store.chapters(work.id)
        val remoteOne = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Closed Work" }
        KavitaServer.saveProgress(remoteOne, KavitaServer.chapters(remoteOne.id).single { it.id.toString() == first.path }, page = 4)
        racer = KavitaProgressSync(client, store, beforeVisit = { item ->
            if (item.chapterId == first.id) {
                val opened = racing().beforeOpening(address, key, second.id)
                read(second, 2)
                racing().closed(second.id, opened.token)
            }
        })

        racing().all(address, key, KavitaImport(client, store).run(key))

        assertEquals(second.id, store.resumeChapter(work.id)?.id)
        assertEquals(listOf(first.id), store.alternatives(work.id).filter { it.reason == LibraryStore.CONFLICT }.map { it.chapterId })
    }

    @Test
    fun `dado um capítulo aberto sem conferir com o servidor, quando sincronizo tudo, então ele também fica de fora`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Unchecked Work" }
        val opened = store.chapters(work.id).single()
        val remoteOne = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Unchecked Work" }
        sync.register(opened.id)
        KavitaServer.saveProgress(remoteOne, KavitaServer.chapters(remoteOne.id).single(), page = 7)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertNull(store.progress(opened.id).position)
    }

    private fun only(title: String): StoredChapter {
        chapter(1.0)
        return store.chapters(store.works().single { it.title == title }.id).single()
    }

    private fun remoteOnly(title: String) = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == title }
        .let { work -> work to KavitaServer.chapters(work.id).single() }

    @Test
    fun `dado o fechamento atrasado de uma abertura antiga, quando o capítulo já reabriu, então ele continua de fora`() {
        val opened = only("Reopened Work")
        val old = sync.beforeOpening(address, key, opened.id)
        sync.beforeOpening(address, key, opened.id)
        sync.closed(opened.id, old.token)
        val (work, remote) = remoteOnly("Reopened Work")
        KavitaServer.saveProgress(work, remote, page = 7)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertNull(store.progress(opened.id).position)
    }

    @Test
    fun `dado o envio depois de fechar, quando o capítulo já reabriu, então nada é enviado por baixo do leitor`() {
        val opened = only("Skipped Work")
        sync.beforeOpening(address, key, opened.id)
        read(opened, 5)

        sync.afterReading(address, key, opened.id)

        val (_, remote) = remoteOnly("Skipped Work")
        assertEquals(0, remote.pagesRead)
    }

    // The sync under test, for the hook that opens a chapter while it runs.
    private var racer: KavitaProgressSync? = null

    private fun racing() = checkNotNull(racer)

    @Test
    fun `dado leitura aqui e avanço em outro capítulo, quando o envio falha depois da adoção, então a escolha continua pendente`() {
        chapter(1.0)
        val work = store.works().single { it.title == "Interrupted Work" }
        val (here, there) = store.chapters(work.id)
        val remoteOne = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == "Interrupted Work" }
        read(here, 3)
        KavitaServer.saveProgress(remoteOne, KavitaServer.chapters(remoteOne.id).single { it.id.toString() == there.path }, page = 8)
        val failing = object : KavitaClient() {
            override fun saveProgress(address: String, key: String, item: SyncItem, page: Int, marker: String?) {
                throw IOException("The network dropped")
            }
        }

        assertFailsWith<IOException> { KavitaProgressSync(failing, store).all(address, key, KavitaImport(client, store).run(key)) }

        assertEquals(here.id, store.resumeChapter(work.id)?.id)
        assertEquals(listOf(there.id), store.alternatives(work.id).filter { it.reason == LibraryStore.CONFLICT }.map { it.chapterId })
    }

    @Test
    fun `dado uma falha do servidor num capítulo, quando sincronizo tudo, então a falha aparece`() {
        val chapter = chapter(1.0)
        read(chapter, 4)
        val failing = object : KavitaClient() {
            override fun progress(address: String, key: String, chapterId: Int): RemoteProgress = throw KavitaListingFailed(500)
        }

        assertFailsWith<KavitaListingFailed> { KavitaProgressSync(failing, store).all(address, key, emptyMap()) }
    }

    @Test
    fun `dado obras importadas com progresso novo no Kavita, quando sincronizo tudo, então adoto sem abrir o capítulo`() {
        val chapter = chapter(7.0)
        KavitaServer.saveProgress(remoteWork, remote(chapter), page = 8)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(8, store.progress(chapter.id).position?.strip()?.page)
    }

    @Test
    fun `dado uma leitura feita aqui, quando sincronizo tudo, então ela vai ao Kavita`() {
        val chapter = chapter(8.0)
        read(chapter, 5)

        sync.all(address, key, KavitaImport(client, store).run(key))

        assertEquals(5, remotePage(chapter))
    }
}
