package app.kamayoq.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.strip
import app.kamayoq.core.reader.TextSettings
import app.kamayoq.data.StoredReadingState
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val database: File = app.getDatabasePath(NAME)
    private val copy = File(app.noBackupFilesDir, "kamayoq-v1.db")
    private val copyOf2 = File(app.noBackupFilesDir, "kamayoq-v2.db")
    private val copyOf4 = File(app.noBackupFilesDir, "kamayoq-v4.db")
    private val copyOf5 = File(app.noBackupFilesDir, "kamayoq-v5.db")
    private val copyOf6 = File(app.noBackupFilesDir, "kamayoq-v6.db")
    private val copyOf7 = File(app.noBackupFilesDir, "kamayoq-v7.db")
    private val unfinishedCopy = File(app.noBackupFilesDir, "kamayoq-v1.db.tmp")

    /** A library as version 1 left it, from the snapshot of that schema. */
    private fun version1() {
        database.parentFile?.mkdirs()
        File(SCHEMA_1).copyTo(database)
        edit {
            execSQL("INSERT INTO work VALUES ('work-1', 'Dom Casmurro', NULL, 'COMICS', NULL)")
            execSQL("INSERT INTO work_version VALUES ('version-1', 'work-1', 'local', 'tree', 'Dom Casmurro', 1, 0)")
            version = 1
        }
    }

    /**
     * A library as version 2 left it, the version on phones before step 4: version 1 plus that
     * migration's own file, with a chapter stopped on a page.
     */
    private fun version2() {
        version1()
        val migration = File(MIGRATION_1).readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        edit {
            migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::execSQL)
            execSQL(
                "INSERT INTO chapter (id, version_id, path, name, kind, number, format, sort_index, present, stamp, " +
                    "needs_check) VALUES ('chapter-1', 'version-1', 'Comics/Dom Casmurro/Cap. 1.cbz', 'Cap. 1', " +
                    "'CHAPTER', 1, 'ARCHIVE', 0, 1, 'stamp', 0)",
            )
            execSQL(
                "INSERT INTO chapter_progress (chapter_id, position_page, position_total, position_at, position_device, " +
                    "complete_reads) VALUES ('chapter-1', 7, 20, 0, 'phone-a', 0)",
            )
            version = 2
        }
    }

    /**
     * A library as version 4 left it, the version on phones before the reading states: version 2
     * plus the next two migrations' own files.
     */
    private fun version4() {
        version2()
        edit {
            for (path in listOf(MIGRATION_2, MIGRATION_3)) {
                val migration = File(path).readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::execSQL)
            }
            version = 4
        }
    }

    /** A library as version 5 left it, the version on phones before the work's details: version 4 plus that migration. */
    private fun version5() {
        version4()
        edit {
            val migration = File(MIGRATION_4).readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::execSQL)
            execSQL("INSERT INTO reading_state (work_id, state, automatic) VALUES ('work-1', 'READING', 0)")
            version = 5
        }
    }

    /** A library as version 6 left it, the version on phones before Home: version 5 plus that migration. */
    private fun version6() {
        version5()
        edit {
            val migration = File(MIGRATION_5).readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::execSQL)
            execSQL("INSERT INTO work_details (work_id, authors, summary, tags) VALUES ('work-1', 'Machado de Assis', '', '')")
            version = 6
        }
    }

    /** A library as version 7 left it, the version on phones before the text reader's settings. */
    private fun version7() {
        version6()
        edit {
            val migration = File(MIGRATION_6).readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::execSQL)
            version = 7
        }
    }

    private fun edit(change: SQLiteDatabase.() -> Unit) =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.change() }

    private fun <T> read(file: File, block: SQLiteDatabase.() -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.block() }

    private fun title(file: File): String = read(file) {
        rawQuery("SELECT title FROM work", null).use { row ->
            row.moveToFirst()
            row.getString(0)
        }
    }

    private fun hasTable(file: File, table: String): Boolean = read(file) {
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'", null).use { it.count == 1 }
    }

    private fun hasColumn(file: File, table: String, column: String): Boolean = read(file) {
        rawQuery("SELECT name FROM pragma_table_info('$table') WHERE name = '$column'", null).use { it.count == 1 }
    }

    @Test
    fun `dado nenhum banco, quando abro, então nasce a biblioteca sem cópia`() {
        val library = LibraryDatabase.open(app, NAME)

        assertTrue(library is LibraryOpening.Opened)
        assertEquals(8, read(database) { version })
        assertFalse(copy.exists())
    }

    @Test
    fun `dado o banco já na versão atual, quando abro de novo, então não copia`() {
        LibraryDatabase.open(app, NAME)

        val library = LibraryDatabase.open(app, NAME)

        assertTrue(library is LibraryOpening.Opened)
        assertFalse(copy.exists())
    }

    @Test
    fun `dado um banco da versão 1, quando abro, então migra e a cópia guarda a versão 1 intacta`() {
        version1()

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(
            listOf("Dom Casmurro"),
            (library as LibraryOpening.Opened).store.works().map { it.title },
        )
        assertEquals(8, read(database) { version })
        assertEquals(1, read(copy) { version })
        assertEquals("Dom Casmurro", title(copy))
        assertFalse(hasColumn(copy, "chapter", "page_count"))
    }

    @Test
    fun `dado um banco da versão 2, quando abro, então migra, a página continua e a cópia guarda a versão 2 intacta`() {
        version2()

        val library = LibraryDatabase.open(app, NAME)

        val store = (library as LibraryOpening.Opened).store
        assertEquals(7, store.progress("chapter-1").position?.strip()?.page)
        assertEquals(8, read(database) { version })
        assertTrue(hasColumn(database, "chapter_progress", "position_cfi"))
        assertEquals(2, read(copyOf2) { version })
        assertFalse(hasColumn(copyOf2, "chapter_progress", "position_cfi"))
        assertTrue(hasColumn(copyOf2, "chapter", "page_count"))
    }

    @Test
    fun `dado um banco da versão 5, quando abro, então migra, a página e o estado continuam, a obra fica sem dados e a cópia guarda a versão 5 intacta`() {
        version5()

        val library = LibraryDatabase.open(app, NAME)

        val store = (library as LibraryOpening.Opened).store
        assertEquals(7, store.progress("chapter-1").position?.strip()?.page)
        assertEquals(StoredReadingState(ReadingState.READING, null), store.readingState("work-1"))
        assertEquals(WorkDetails.NONE, store.details("work-1"))
        assertEquals(8, read(database) { version })
        assertEquals(5, read(copyOf5) { version })
        assertFalse(hasTable(copyOf5, "work_details"))
        assertTrue(hasTable(copyOf5, "reading_state"))
    }

    @Test
    fun `dado um banco da versão 6, quando abro, então migra, a página, o estado e os dados continuam, nada chegou e a cópia guarda a versão 6 intacta`() {
        version6()

        val library = LibraryDatabase.open(app, NAME)

        val store = (library as LibraryOpening.Opened).store
        assertEquals(7, store.progress("chapter-1").position?.strip()?.page)
        assertEquals(StoredReadingState(ReadingState.READING, null), store.readingState("work-1"))
        assertEquals(listOf("Machado de Assis"), store.details("work-1").authors)
        assertNull(store.addedAt("work-1"))
        assertTrue(store.arrivals("work-1").isEmpty())
        assertEquals(8, read(database) { version })
        assertEquals(6, read(copyOf6) { version })
        assertFalse(hasColumn(copyOf6, "chapter", "arrived_at"))
        assertTrue(hasTable(copyOf6, "work_details"))
    }

    @Test
    fun `dado um banco da versão 4, quando abro, então migra, a página continua, a obra fica sem estado e a cópia guarda a versão 4 intacta`() {
        version4()

        val library = LibraryDatabase.open(app, NAME)

        val store = (library as LibraryOpening.Opened).store
        assertEquals(7, store.progress("chapter-1").position?.strip()?.page)
        assertEquals(StoredReadingState.NONE, store.readingState("work-1"))
        assertEquals(8, read(database) { version })
        assertEquals(4, read(copyOf4) { version })
        assertFalse(hasTable(copyOf4, "reading_state"))
        assertTrue(hasTable(copyOf4, "edition_offer"))
    }

    @Test
    fun `dado um banco da versão 7, quando abro, então migra, a página e o estado continuam, a obra abre com os ajustes padrão e a cópia guarda a versão 7 intacta`() {
        version7()

        val library = LibraryDatabase.open(app, NAME)

        val store = (library as LibraryOpening.Opened).store
        assertEquals(7, store.progress("chapter-1").position?.strip()?.page)
        assertEquals(StoredReadingState(ReadingState.READING, null), store.readingState("work-1"))
        assertEquals(TextSettings.DEFAULT, store.textSettings("work-1"))
        assertEquals(8, read(database) { version })
        assertEquals(7, read(copyOf7) { version })
        assertFalse(hasTable(copyOf7, "work_text_look"))
        assertTrue(hasColumn(copyOf7, "chapter", "arrived_at"))
    }

    @Test
    fun `dado uma cópia pela metade de uma tentativa morta, quando abro, então ela não trava o app`() {
        version1()
        app.noBackupFilesDir.mkdirs()
        unfinishedCopy.writeText("half a copy")

        val library = LibraryDatabase.open(app, NAME)

        assertTrue(library is LibraryOpening.Opened)
        assertEquals("Dom Casmurro", title(copy))
        assertFalse(unfinishedCopy.exists())
    }

    @Test
    fun `dado uma cópia estragada com o nome certo, quando abro, então ela é refeita`() {
        version1()
        app.noBackupFilesDir.mkdirs()
        copy.writeText("not a database")

        val library = LibraryDatabase.open(app, NAME)

        assertTrue(library is LibraryOpening.Opened)
        assertEquals("Dom Casmurro", title(copy))
    }

    @Test
    fun `dado que a cópia não pode ser feita, quando abro, então não migra`() {
        version1()
        // A folder with something inside, where the unfinished copy has to go.
        File(unfinishedCopy, "blocker").apply { parentFile?.mkdirs() }.writeText("x")

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NOT_OPENED), library)
        assertEquals(1, read(database) { version })
        assertFalse(hasColumn(database, "chapter", "page_count"))
    }

    @Test
    fun `dado uma migração que falha no meio, quando abro, então o banco volta como estava e a cópia fica`() {
        version1()
        // Fails on the first CREATE TABLE of the migration, after its ALTER TABLE statements.
        edit { execSQL("CREATE TABLE kavita_server (id TEXT)") }

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NOT_OPENED), library)
        assertEquals(1, read(database) { version })
        assertFalse(hasColumn(database, "chapter", "page_count"))
        assertEquals("Dom Casmurro", title(database))
        assertEquals("Dom Casmurro", title(copy))
    }

    @Test
    fun `dado uma migração que falhou, quando abro de novo, então a tela volta e nada trava`() {
        version1()
        edit { execSQL("CREATE TABLE kavita_server (id TEXT)") }
        LibraryDatabase.open(app, NAME)

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NOT_OPENED), library)
        assertEquals("Dom Casmurro", title(copy))
    }

    @Test
    fun `dado um banco de versão mais nova que o app, quando abro, então pede atualização sem copiar nem mexer`() {
        version1()
        edit { version = 99 }

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NEWER_VERSION), library)
        assertEquals(99, read(database) { version })
        assertFalse(copy.exists())
    }

    @Test
    fun `dado um banco corrompido, quando abro, então a tela aparece e o arquivo não é apagado`() {
        database.parentFile?.mkdirs()
        val damaged = ByteArray(8192) { 7 }
        database.writeBytes(damaged)

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NOT_OPENED), library)
        assertArrayEquals(damaged, database.readBytes())
    }

    @Test
    fun `dado um banco com uma tabela danificada, quando abro, então a tela aparece em vez de cair depois`() {
        version1()
        // Labeled as the current version, so the opening goes straight to the check.
        edit { version = 4 }
        val (page, size) = read(database) {
            val root = rawQuery("SELECT rootpage FROM sqlite_master WHERE name = 'work'", null).use { row ->
                row.moveToFirst()
                row.getLong(0)
            }
            root to pageSize
        }
        RandomAccessFile(database, "rw").use { file ->
            file.seek((page - 1) * size)
            file.write(ByteArray(size.toInt()) { -1 })
        }

        val library = LibraryDatabase.open(app, NAME)

        assertEquals(LibraryOpening.Failed(LibraryFailure.NOT_OPENED), library)
        assertTrue(database.exists())
    }

    private companion object {
        const val NAME = "library-test.db"
        const val SCHEMA_1 = "../data/src/main/sqldelight/databases/1.db"
        const val MIGRATION_1 = "../data/src/main/sqldelight/migrations/1.sqm"
        const val MIGRATION_2 = "../data/src/main/sqldelight/migrations/2.sqm"
        const val MIGRATION_3 = "../data/src/main/sqldelight/migrations/3.sqm"
        const val MIGRATION_4 = "../data/src/main/sqldelight/migrations/4.sqm"
        const val MIGRATION_5 = "../data/src/main/sqldelight/migrations/5.sqm"
        const val MIGRATION_6 = "../data/src/main/sqldelight/migrations/6.sqm"
    }
}
