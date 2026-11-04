package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.backup.BackupFormat
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupExportTest {

    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)
    private val now = Instant.parse("2026-10-28T21:48:30Z")
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    @AfterTest
    fun close() = driver.close()

    /** The backups folder in memory. [stored] changes what is kept, as a storage that fails would. */
    private class MemoryFolder(
        private val taken: Set<String> = emptySet(),
        private val failWrite: Boolean = false,
        private val failName: Boolean = false,
        private val tellsName: Boolean = true,
        private val stored: (String) -> String = { it },
    ) : BackupFolder {
        val files = mutableMapOf<String, String>()
        private val names = mutableMapOf<String, String>()

        override fun create(name: String): String {
            val given = if (name in taken) name.replace(".json", " (1).json") else name
            val id = "doc:$given"
            files[id] = ""
            names[id] = given
            return id
        }

        override fun nameOf(id: String): String? {
            if (failName) throw IOException("The provider gave no answer")
            return if (tellsName) names.getValue(id) else null
        }

        override fun write(id: String, text: String) {
            if (failWrite) throw IOException("No space left")
            files[id] = stored(text)
        }

        override fun read(id: String): String = files.getValue(id)

        override fun delete(id: String) {
            files.remove(id)
        }
    }

    private fun readOneChapter() {
        val path = "Comics/Sakamoto Days/Cap. 001.cbz"
        store.importLocal("content://tree/Kamayoq", FolderConvention.read(listOf(path)), mapOf(path to FileStamp(1_000, 5)))
        val chapter = store.chapters(store.works().single().id).single()
        val at = Instant.parse("2026-10-25T14:10:00Z")
        store.saveProgress(
            chapter.id,
            ChapterProgress(ReadMark(at, ReadMarkSource.READ_TO_END), StripPosition(40, 40, at, "phone-a"), 1),
        )
    }

    @Test
    fun `dado um backup que o armazenamento guarda igual, então fica o arquivo com o nome da hora e o backup de tudo`() {
        readOneChapter()
        val folder = MemoryFolder()

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertEquals(BackupResult.Written("kamayoq_2026-10-28_18-48.json"), result)
        val kept = BackupFormat.read(folder.files.values.single())
        assertEquals(store.backup("r102", now), kept)
    }

    @Test
    fun `dado um nome já ocupado na pasta, então a mensagem diz o nome que a pasta deu ao arquivo`() {
        readOneChapter()
        val folder = MemoryFolder(taken = setOf("kamayoq_2026-10-28_18-48.json"))

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertEquals(BackupResult.Written("kamayoq_2026-10-28_18-48 (1).json"), result)
        assertEquals("kamayoq_2026-10-28_18-48 (1).json", folder.nameOf(folder.files.keys.single()))
    }

    @Test
    fun `dado um armazenamento que guarda o arquivo pela metade, então o arquivo é apagado e o resultado diz que não confere`() {
        readOneChapter()
        val folder = MemoryFolder(stored = { it.take(it.length / 2) })

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertEquals(BackupResult.Mismatch, result)
        assertTrue(folder.files.isEmpty(), "Left: ${folder.files.keys}")
    }

    @Test
    fun `dado um armazenamento que guarda outro backup inteiro, então o arquivo é apagado e o resultado diz que não confere`() {
        readOneChapter()
        val folder = MemoryFolder(stored = { it.replace("\"completeReads\": 1", "\"completeReads\": 0") })

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertEquals(BackupResult.Mismatch, result)
        assertTrue(folder.files.isEmpty(), "Left: ${folder.files.keys}")
    }

    @Test
    fun `dado um provedor que cria o arquivo e falha ao dizer o nome, então o arquivo criado é apagado e o erro vai no resultado`() {
        readOneChapter()
        val folder = MemoryFolder(failName = true)

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertIs<IOException>(assertIs<BackupResult.WriteFailed>(result).cause)
        assertTrue(folder.files.isEmpty(), "Left: ${folder.files.keys}")
    }

    @Test
    fun `dado um provedor que deu outro nome e não diz qual, então o backup fica e o resultado não inventa um nome`() {
        readOneChapter()
        val folder = MemoryFolder(taken = setOf("kamayoq_2026-10-28_18-48.json"), tellsName = false)

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertEquals(BackupResult.Written(fileName = null), result)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `dado uma gravação que falha no meio, então o arquivo criado é apagado e o erro vai no resultado`() {
        readOneChapter()
        val folder = MemoryFolder(failWrite = true)

        val result = BackupExport(store, folder).run("r102", now, saoPaulo)

        assertIs<IOException>(assertIs<BackupResult.WriteFailed>(result).cause)
        assertTrue(folder.files.isEmpty(), "Left: ${folder.files.keys}")
    }
}
