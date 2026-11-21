package app.kamayoq.data.kavita

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.WorkCovers
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaImportRunTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    private val coverFolder: File = Files.createTempDirectory("covers").toFile()
    private val covers = WorkCovers(coverFolder)

    @AfterTest
    fun close() {
        driver.close()
        coverFolder.deleteRecursively()
    }

    private fun import(vararg libraries: Int) {
        store.linkKavita(KavitaServer.url, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, libraries.toList())
        KavitaImport(KavitaClient(), store, covers).run(KavitaServer.authKey)
    }

    private fun stored(title: String) = store.works().single { it.title == title }.id

    /** The rows [block] writes, as SQLite counts them on the test's connection. */
    private fun changes(block: () -> Unit): Long {
        fun total() = driver.executeQuery(null, "SELECT total_changes()", { cursor ->
            cursor.next()
            QueryResult.Value(checkNotNull(cursor.getLong(0)))
        }, 0).value
        val before = total()
        block()
        return total() - before
    }

    @Test
    fun `dado a biblioteca de teste marcada, quando importo, então a Biblioteca tem duas obras do Kavita com os capítulos certos`() {
        import(KavitaServer.libraryId)

        assertEquals(listOf("First Work", "Second Work"), store.works().map { it.title }.sorted())
        val second = store.works().single { it.title == "Second Work" }
        assertEquals(LibraryStore.KAVITA, second.origin)
        assertEquals(listOf(1.0, 2.0), store.chapters(second.id).map { it.number })
        assertEquals(listOf(listOf(12), listOf(3, 3)), store.backup("r1", java.time.Instant.EPOCH).works.sortedBy { it.title }.map { work -> work.chapters.map { it.pageCount } })
    }

    @Test
    fun `dado nenhuma biblioteca marcada, quando importo, então nada entra`() {
        import()

        assertEquals(emptyList(), store.works())
    }

    @Test
    fun `dado uma importação feita, quando importo de novo, então os ids locais não mudam`() {
        import(KavitaServer.libraryId)
        val before = store.works().map { it.id }

        KavitaImport(KavitaClient(), store).run(KavitaServer.authKey)

        assertEquals(before, store.works().map { it.id })
    }

    @Test
    fun `dado a biblioteca de livros marcada, quando importo, então o EPUB entra como livro, sem número`() {
        import(KavitaServer.bookLibraryId)

        val book = store.works().single { it.title == "Same Title" }
        assertEquals(app.kamayoq.core.library.WorkType.BOOKS, book.type)
        assertEquals(setOf(app.kamayoq.core.library.ChapterFormat.EPUB), store.chapters(book.id).map { it.format }.toSet())
        assertEquals(setOf<Double?>(null), store.chapters(book.id).map { it.number }.toSet())
    }

    @Test
    fun `dado uma série com ComicInfo, quando importo, então a obra guarda autores, sinopse, etiquetas e a situação desconhecida`() {
        import(KavitaServer.describedLibraryId)

        val details = store.details(stored("Info Work"))
        assertEquals(
            WorkDetails(listOf("Ann Writer", "Bo Second"), "A summary of Info Work.", listOf("Action", "Fantasy", "Magic", "School"), Publication.UNKNOWN),
            details.copy(authors = details.authors.sorted(), tags = details.tags.sorted()),
        )
    }

    @Test
    fun `dado séries concluída e com capítulos faltando, quando importo, então cada obra guarda a sua situação`() {
        import(KavitaServer.describedLibraryId)

        assertEquals(Publication(PublicationStatus.COMPLETED, 0), store.details(stored("Ended Work")).publication)
        assertEquals(Publication(PublicationStatus.COMPLETED, 4), store.details(stored("Short Work")).publication)
    }

    @Test
    fun `dado uma série com capa, quando importo, então a capa fica guardada com a marca que o Kavita dá`() {
        import(KavitaServer.describedLibraryId)

        val id = stored("Info Work")
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), covers.file(id)?.readBytes()?.take(4)?.map { it.toInt() and 0xFF })
        assertEquals(KavitaServer.coverNames(KavitaServer.describedLibraryId).getValue("Info Work"), store.coverStamp(id))
    }

    @Test
    fun `dado um EPUB sem imagem de capa, quando importo, então não fica capa nenhuma, e a obra tem a provisória`() {
        import(KavitaServer.bookLibraryId)

        val id = stored("Lone Book")
        assertEquals(null, covers.file(id))
        assertEquals(null, store.coverStamp(id))
    }

    @Test
    fun `dado um EPUB sem imagem de capa já importado, quando importo de novo, então a capa não grava nada no banco`() {
        import(KavitaServer.bookLibraryId)

        // The same import, without covers and with them: what the covers write is the difference.
        val withoutCovers = changes { KavitaImport(KavitaClient(), store).run(KavitaServer.authKey) }
        val withCovers = changes { KavitaImport(KavitaClient(), store, covers).run(KavitaServer.authKey) }

        assertEquals(withoutCovers, withCovers)
    }

    @Test
    fun `dado a capa já guardada com a mesma marca, quando importo de novo, então ela não é baixada outra vez`() {
        import(KavitaServer.describedLibraryId)
        val cover = checkNotNull(covers.file(stored("Info Work")))
        cover.writeBytes(byteArrayOf(1, 2, 3))

        KavitaImport(KavitaClient(), store, covers).run(KavitaServer.authKey)

        assertEquals(listOf<Byte>(1, 2, 3), cover.readBytes().toList())
    }

    @Test
    fun `dado a marca guardada mas o arquivo da capa sumido, quando importo de novo, então a capa volta`() {
        import(KavitaServer.describedLibraryId)
        val id = stored("Info Work")
        covers.drop(id)

        KavitaImport(KavitaClient(), store, covers).run(KavitaServer.authKey)

        assertEquals(0x89, covers.file(id)?.readBytes()?.first()?.toInt()?.and(0xFF))
    }

    @Test
    fun `dado a pasta das capas sem como gravar, quando importo, então a importação termina e guarda os dados das obras`() {
        // A file where the folder should be: every cover's write fails, as on a full disk.
        val blocked = File(coverFolder, "blocked").apply { writeText("not a folder") }
        store.linkKavita(KavitaServer.url, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, listOf(KavitaServer.describedLibraryId))

        KavitaImport(KavitaClient(), store, WorkCovers(blocked)).run(KavitaServer.authKey)

        assertEquals("A summary of Info Work.", store.details(stored("Info Work")).summary)
        assertEquals(null, store.coverStamp(stored("Info Work")))
    }
}
