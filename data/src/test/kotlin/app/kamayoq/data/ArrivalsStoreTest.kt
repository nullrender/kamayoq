package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Slice 4 of the v1 interface: what arrives after the zero mark of each folder and Kavita library
// (reader-design FEATURES, part 2).
class ArrivalsStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3AOutra"
    private val server = "server-1"
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val store = newStore()

    private fun newStore(): LibraryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        drivers += driver
        return LibraryStore(driver)
    }

    @AfterTest
    fun close() = drivers.forEach { it.close() }

    private val first = Instant.parse("2026-11-20T10:00:00Z")
    private val later = Instant.parse("2026-11-21T09:00:00Z")
    private val latest = Instant.parse("2026-11-21T13:00:00Z")

    private fun LibraryStore.read(at: Instant, vararg paths: String, folder: String = source) =
        importLocal(folder, FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(1_000, 5) }, at = at)

    private fun LibraryStore.work(title: String) = works().single { it.title == title }

    private fun LibraryStore.chapter(title: String, name: String) = chapters(work(title).id).single { it.name == name }

    private fun LibraryStore.arrivalsOf(title: String) = arrivals(work(title).id).mapKeys { (id, _) -> chapters(work(title).id).single { it.id == id }.name }

    @Test
    fun `dado a primeira leitura de uma pasta, então nenhuma obra é adicionada e nenhum capítulo chega`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 001.cbz")

        assertNull(store.addedAt(store.work("Sakamoto Days").id))
        assertEquals(emptyMap(), store.arrivalsOf("Sakamoto Days"))
    }

    @Test
    fun `dado uma pasta já lida, quando aparece uma obra nova, então ela é adicionada na hora da leitura e os capítulos dela não chegam`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")

        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 001.cbz", "Comics/Gantz/Cap. 002.cbz")

        assertEquals(later, store.addedAt(store.work("Gantz").id))
        assertEquals(emptyMap(), store.arrivalsOf("Gantz"))
        assertNull(store.addedAt(store.work("Sakamoto Days").id))
    }

    @Test
    fun `dado uma pasta já lida, quando uma obra que já existia ganha um capítulo, então só ele chega, na hora da leitura`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")

        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        assertEquals(mapOf("Cap. 002" to later), store.arrivalsOf("Sakamoto Days"))
    }

    @Test
    fun `dado um capítulo que chegou, quando a pasta é lida de novo, então a hora da chegada continua a primeira`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")
        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        store.read(latest, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")

        assertEquals(mapOf("Cap. 002" to later), store.arrivalsOf("Sakamoto Days"))
    }

    @Test
    fun `dado uma pasta lida pela primeira vez vazia, quando aparece uma obra, então ela é adicionada`() {
        store.read(first)

        store.read(later, "Comics/Gantz/Cap. 001.cbz")

        assertEquals(later, store.addedAt(store.work("Gantz").id))
    }

    @Test
    fun `dado um capítulo e uma obra que sumiram da pasta, quando voltam, então não contam como chegada`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz", "Comics/Gantz/Cap. 001.cbz")
        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz")

        store.read(latest, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz", "Comics/Gantz/Cap. 001.cbz")

        assertEquals(emptyMap(), store.arrivalsOf("Sakamoto Days"))
        assertNull(store.addedAt(store.work("Gantz").id))
    }

    @Test
    fun `dado uma pasta já lida, quando outra pasta é escolhida e lida pela primeira vez, então é o marco zero dela`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")

        store.read(later, "Comics/Gantz/Cap. 001.cbz", folder = other)

        assertNull(store.addedAt(store.work("Gantz").id))
    }

    private val novel = "Novels/Release That Witch/Release That Witch.epub"

    private fun section(number: Int) =
        ReadingUnit(novel, "Capítulo $number", UnitKind.CHAPTER, number.toDouble(), UnitContent.SplitEpub, tocKey = "OEBPS/c$number.xhtml")

    private fun readNovel(at: Instant, stamp: FileStamp, vararg units: ReadingUnit) {
        val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units.toList())
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(novel to stamp), at = at)
    }

    @Test
    fun `dado um EPUB dividido já lido, quando o arquivo ganha um capítulo, então só o de título novo chega e os de antes são edição nova sem chegada`() {
        readNovel(first, FileStamp(1_000, 5), section(1), section(2))

        readNovel(later, FileStamp(1_200, 6), section(1), section(2), section(3))

        assertEquals(mapOf("Capítulo 3" to later), store.arrivalsOf("Release That Witch"))
    }

    @Test
    fun `dado uma pasta lida antes de restaurar um backup, quando ela é lida depois da restauração, então é marco zero outra vez`() {
        val original = newStore()
        original.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")
        store.read(first)

        assertTrue(store.restore(original.backup("r700", later)))
        store.read(latest, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Gantz/Cap. 001.cbz")

        assertNull(store.addedAt(store.work("Gantz").id))
        assertNull(store.addedAt(store.work("Sakamoto Days").id))
    }

    private fun remoteChapter(id: Int) = ImportedChapter(
        path = id.toString(),
        name = "Cap. $id",
        kind = UnitKind.CHAPTER,
        number = id.toDouble(),
        format = ChapterFormat.ARCHIVE,
        stamp = "20|100",
        pageCount = 20,
        remoteFile = "Cap. $id.cbz",
        remoteVolumeId = 7,
    )

    private fun series(id: Int, title: String, vararg chapters: Int, library: Int = 2) =
        ImportedWork(id.toString(), title, WorkType.COMICS, null, library, chapters.map(::remoteChapter))

    @Test
    fun `dado a primeira importação de uma biblioteca do Kavita, então nada é adicionado nem chega`() {
        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10, 11)), at = first)

        assertNull(store.addedAt(store.work("Sakamoto Days").id))
        assertEquals(emptyMap(), store.arrivalsOf("Sakamoto Days"))
    }

    @Test
    fun `dado uma biblioteca do Kavita já importada, quando chegam uma série e um capítulo novos, então a série é adicionada e o capítulo chega`() {
        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10)), at = first)

        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10, 11), series(2, "Gantz", 20)), at = later)

        assertEquals(mapOf("Cap. 11" to later), store.arrivalsOf("Sakamoto Days"))
        assertEquals(later, store.addedAt(store.work("Gantz").id))
        assertEquals(emptyMap(), store.arrivalsOf("Gantz"))
    }

    @Test
    fun `dado uma biblioteca do Kavita já importada, quando outra biblioteca é marcada, então a primeira importação dela é o marco zero dela`() {
        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10)), at = first)

        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10), series(5, "Shadow Slave", 50, library = 3)), at = later)

        assertNull(store.addedAt(store.work("Shadow Slave").id))
        assertEquals(emptyMap(), store.arrivalsOf("Shadow Slave"))
    }

    @Test
    fun `dado um capítulo do Kavita que voltou com outro id, quando a regra 8b o religa, então não conta como chegada`() {
        store.importKavita(server, listOf(series(1, "Sakamoto Days", 10, 11)), at = first)

        val relinked = remoteChapter(11).copy(path = "99")
        store.importKavita(server, listOf(ImportedWork("1", "Sakamoto Days", WorkType.COMICS, null, 2, listOf(remoteChapter(10), relinked))), at = later)

        assertEquals(emptyMap(), store.arrivalsOf("Sakamoto Days"))
    }

    private val opened = Instant.parse("2026-11-21T11:00:00Z")

    @Test
    fun `dado um capítulo que chegou, quando a pessoa abre a Obra, então ele vem como novo uma vez e a visita fica gravada`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")
        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val work = store.work("Sakamoto Days").id

        val news = store.visitWork(work, opened)

        assertEquals(mapOf(store.chapter("Sakamoto Days", "Cap. 002").id to later), news)
        assertEquals(opened, store.lastVisit(work))
        assertEquals(emptyMap(), store.visitWork(work, latest))
        assertEquals(latest, store.lastVisit(work))
    }

    @Test
    fun `dado um capítulo que chegou depois da última visita, quando a pessoa abre a Obra outra vez, então só ele vem como novo`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")
        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val work = store.work("Sakamoto Days").id
        store.visitWork(work, opened)

        store.read(latest, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz", "Comics/Sakamoto Days/Cap. 003.cbz")

        assertEquals(mapOf(store.chapter("Sakamoto Days", "Cap. 003").id to latest), store.visitWork(work, latest.plusSeconds(60)))
    }

    @Test
    fun `dado um capítulo que chegou e já foi lido, quando a pessoa abre a Obra, então ele não vem como novo`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")
        store.read(later, "Comics/Sakamoto Days/Cap. 001.cbz", "Comics/Sakamoto Days/Cap. 002.cbz")
        val work = store.work("Sakamoto Days").id
        store.markRead(work, listOf(store.chapter("Sakamoto Days", "Cap. 002").id), latest)

        assertEquals(emptyMap(), store.visitWork(work, latest.plusSeconds(60)))
    }

    @Test
    fun `dado uma obra nunca aberta, então não há última visita`() {
        store.read(first, "Comics/Sakamoto Days/Cap. 001.cbz")

        assertNull(store.lastVisit(store.work("Sakamoto Days").id))
    }
}
