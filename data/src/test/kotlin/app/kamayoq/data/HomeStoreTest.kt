package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Slice 4 of the v1 interface: what Home reads from the store (reader-design FEATURES, part 2).
class HomeStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private val first = Instant.parse("2026-11-20T10:00:00Z")
    private val later = Instant.parse("2026-11-21T09:00:00Z")
    private val latest = Instant.parse("2026-11-21T13:00:00Z")

    private fun read(at: Instant, vararg paths: String) =
        store.importLocal(source, FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(1_000, 5) }, at = at)

    private fun work(title: String) = store.works().single { it.title == title }

    private fun chapter(title: String, name: String) = store.chapters(work(title).id).single { it.name == name }

    // In Reading, as the end of a first chapter or the person would leave it: halfway through the
    // first chapter a comic has no state yet (FEATURES, part 3).
    private fun readUpTo(title: String, name: String, page: Int, at: Instant) {
        store.saveProgress(chapter(title, name).id, ChapterProgress(null, StripPosition(page, 20, at, "phone-a"), 0))
        store.setReadingState(work(title).id, ReadingState.READING)
    }

    private val folder = arrayOf(
        "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz",
        "Comics/Gantz/Cap. 001.cbz",
        "Comics/Kaoru Hana/Cap. 001.cbz",
    )

    @Test
    fun `dado uma biblioteca lida pela primeira vez e nada lido, então o Início está vazio`() {
        read(first, *folder)

        assertTrue(store.home().isEmpty)
    }

    @Test
    fun `dado duas obras lidas, então Continuar lendo traz a mais recente primeiro, com o capítulo onde continua e o lugar`() {
        read(first, *folder)
        readUpTo("Gantz", "Cap. 001", 5, later)
        readUpTo("Sakamoto Days", "Cap. 002", 12, latest)

        val items = store.home().continueReading

        assertEquals(listOf("Sakamoto Days", "Gantz"), items.map { it.work.title })
        val sakamoto = items.first()
        assertEquals("Cap. 002", sakamoto.target.name)
        assertTrue(sakamoto.goesOn)
        assertEquals(12, (sakamoto.position as StripPosition).page)
        assertEquals(latest, sakamoto.lastReadAt)
        assertEquals(2, sakamoto.chapterCount)
    }

    @Test
    fun `dado uma obra aberta e ainda sem estado, então ela não entra em Continuar lendo`() {
        read(first, *folder)
        store.saveProgress(chapter("Gantz", "Cap. 001").id, ChapterProgress(null, StripPosition(5, 20, later, "phone-a"), 0))

        assertEquals(emptyList(), store.home().continueReading)
    }

    @Test
    fun `dado uma obra lida e posta em pausa, então ela sai de Continuar lendo`() {
        read(first, *folder)
        readUpTo("Gantz", "Cap. 001", 5, later)

        store.setReadingState(work("Gantz").id, ReadingState.ON_HOLD)

        assertEquals(emptyList(), store.home().continueReading)
    }

    @Test
    fun `dado um capítulo que chegou numa obra em Quero ler, então ele está em Chegou, e marcar lido o tira de lá`() {
        read(first, *folder)
        store.setReadingState(work("Kaoru Hana").id, ReadingState.WANT_TO_READ)
        read(later, *folder, "Comics/Kaoru Hana/Cap. 002.cbz", "Comics/Kaoru Hana/Cap. 003.cbz")

        val group = store.home().arrived.single()

        assertEquals("Kaoru Hana", group.work.title)
        assertEquals(2, group.count)
        // Both came in the same reading: the later in the work comes first.
        assertEquals(listOf("Cap. 003", "Cap. 002"), group.chapters.map { it.chapter.name })
        assertEquals(later, group.latest)

        store.markRead(group.work.id, group.chapters.map { it.chapter.id }, latest)

        assertEquals(emptyList(), store.home().arrived)
    }

    @Test
    fun `dado um capítulo que chegou numa obra em Lendo, quando a pessoa abre a Obra, então ele sai de Chegou`() {
        read(first, *folder)
        store.setReadingState(work("Kaoru Hana").id, ReadingState.READING)
        read(later, *folder, "Comics/Kaoru Hana/Cap. 002.cbz")

        store.visitWork(work("Kaoru Hana").id, latest)

        assertEquals(emptyList(), store.home().arrived)
    }

    @Test
    fun `dado uma obra nova na pasta, então ela está em Adicionadas com os capítulos contados até a pessoa abrir a Obra`() {
        read(first, *folder)
        read(later, *folder, "Comics/Tower of God/Cap. 001.cbz", "Comics/Tower of God/Cap. 002.cbz")

        val added = store.home().added.single()

        assertEquals("Tower of God", added.work.title)
        assertEquals(2, added.chapterCount)
        assertEquals(later, added.addedAt)

        store.visitWork(added.work.id, latest)

        assertEquals(emptyList(), store.home().added)
    }

    @Test
    fun `dado uma obra que sumiu da pasta, então ela some do Início`() {
        read(first, *folder)
        readUpTo("Gantz", "Cap. 001", 5, later)
        read(later, *folder, "Comics/Tower of God/Cap. 001.cbz")

        read(latest, "Comics/Sakamoto Days/Cap. 001.cbz")

        assertTrue(store.home().isEmpty)
    }
}
