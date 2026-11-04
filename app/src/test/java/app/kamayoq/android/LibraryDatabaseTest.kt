package app.kamayoq.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val database: File = app.getDatabasePath(NAME)
    private val copy = File(app.noBackupFilesDir, "kamayoq-v1.db")
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

    private fun hasColumn(file: File, table: String, column: String): Boolean = read(file) {
        rawQuery("SELECT name FROM pragma_table_info('$table') WHERE name = '$column'", null).use { it.count == 1 }
    }

    @Test
    fun `dado nenhum banco, quando abro, então nasce a biblioteca sem cópia`() {
        val library = LibraryDatabase.open(app, NAME)

        assertTrue(library is LibraryOpening.Opened)
        assertEquals(2, read(database) { version })
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
        assertEquals(2, read(database) { version })
        assertEquals(1, read(copy) { version })
        assertEquals("Dom Casmurro", title(copy))
        assertFalse(hasColumn(copy, "chapter", "page_count"))
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
        edit { version = 2 }
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
    }
}
