package app.kamayoq.data.kavita

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.data.LibraryStore
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaImportRunTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private fun import(vararg libraries: Int) {
        store.linkKavita(KavitaServer.url, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, libraries.toList())
        KavitaImport(KavitaClient(), store).run(KavitaServer.authKey)
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
}
