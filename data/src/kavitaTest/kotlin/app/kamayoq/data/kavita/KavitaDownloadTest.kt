package app.kamayoq.data.kavita

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.data.ArchiveOpening
import app.kamayoq.data.LibraryStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KavitaDownloadTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)
    private val cache: File = Files.createTempDirectory("kamayoq-kavita").toFile()
    private val generated = File(
        File(System.getenv("KAVITA_FIXTURES") ?: error("KAVITA_FIXTURES is not set")).parentFile,
        "comics/First Work/First Work Ch. 001.cbz",
    )

    @AfterTest
    fun close() {
        driver.close()
        cache.deleteRecursively()
    }

    private fun imported() = run {
        store.linkKavita(KavitaServer.url, "0.9.1.4", accountId = 1)
        store.markLibraries(checkNotNull(store.kavitaServer()).id, listOf(KavitaServer.libraryId))
        KavitaImport(KavitaClient(), store).run(KavitaServer.authKey)
        store.chapters(store.works().single { it.title == "First Work" }.id).single()
    }

    @Test
    fun `dado o capítulo de 12 páginas, quando o baixo e abro, então as páginas saem na ordem natural e o arquivo é o gerado`() {
        val chapter = imported()
        val files = KavitaChapterFiles(KavitaClient(), File(cache, "kavita"))

        val file = files.file(KavitaServer.url, KavitaServer.authKey, chapter).file

        assertContentEquals(generated.readBytes(), file.readBytes())
        val archives = ArchiveOpening(store, File(cache, "reader"))
        val opened = archives.open(archives.begin(), chapter, file.inputStream())
        assertEquals(12, opened.pages.size)
        ZipFile(generated).use { zip ->
            opened.pages.forEachIndexed { index, key ->
                val page = zip.getInputStream(zip.getEntry("${index + 1}.png")).readBytes()
                assertContentEquals(page, opened.open(key).use { it.readBytes() })
            }
        }
        archives.release(opened)
    }

    @Test
    fun `dado o capítulo já baixado e o Kavita fora do ar, quando peço o arquivo, então vem o guardado`() {
        val chapter = imported()
        val files = KavitaChapterFiles(KavitaClient(), File(cache, "kavita"))
        files.file(KavitaServer.url, KavitaServer.authKey, chapter)

        val kept = files.file("http://localhost:1", KavitaServer.authKey, chapter).file

        assertContentEquals(generated.readBytes(), kept.readBytes())
    }

    @Test
    fun `dado o Kavita fora do ar e nada baixado, quando peço o arquivo, então a falha aparece`() {
        val chapter = imported()
        val files = KavitaChapterFiles(KavitaClient(), File(cache, "kavita"))

        assertFailsWith<IOException> { files.file("http://localhost:1", KavitaServer.authKey, chapter) }
    }

    @Test
    fun `dado um download que ninguém mais espera, quando peço o arquivo, então ele para com falha e nada sobra`() {
        // A screen that left must not hold every other opening until its download ends.
        val chapter = imported()
        val folder = File(cache, "kavita")
        val files = KavitaChapterFiles(KavitaClient(), folder)

        assertFailsWith<IOException> { files.file(chapter, stillWanted = { false }) { KavitaServer.url to KavitaServer.authKey } }

        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    fun `dado um download que a tela deixa de esperar no meio, quando peço o arquivo, então ele para com falha e nada sobra`() {
        // The test above stops before the first piece.
        val chapter = imported()
        val folder = File(cache, "kavita")
        val files = KavitaChapterFiles(KavitaClient(), folder)
        var asked = 0

        assertFailsWith<IOException> { files.file(chapter, stillWanted = { asked++ == 0 }) { KavitaServer.url to KavitaServer.authKey } }

        assertEquals(2, asked)
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }
}
