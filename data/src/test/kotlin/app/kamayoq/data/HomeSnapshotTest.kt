package app.kamayoq.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ReadingState
import java.io.File
import java.time.Instant
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Home is read while the folder and Kavita are read on other threads (the app does both as it opens):
// what Home shows must come from one moment of the store.
class HomeSnapshotTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val folder = createTempDirectory("home-snapshot").toFile()
    private val file = File(folder, "library.db")

    // WAL, as on Android: a reader in a transaction keeps its moment while another connection writes.
    private fun driver() = JdbcSqliteDriver(
        "jdbc:sqlite:${file.path}",
        Properties().apply {
            put("foreign_keys", "true")
            put("journal_mode", "WAL")
        },
        LibraryStore.schema,
    )

    private val writing = driver()
    private val writer = LibraryStore(writing)

    /** A driver that, the first time Home reads the arrivals, lets [between] write first. */
    private class WritesBetween(private val inner: SqlDriver, private val between: () -> Unit) : SqlDriver by inner {
        private var done = false

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (!done && sql.contains("arrived_at IS NOT NULL")) {
                done = true
                between()
            }
            return inner.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private val reading = driver()

    @AfterTest
    fun close() {
        reading.close()
        writing.close()
        folder.deleteRecursively()
    }

    private val first = Instant.parse("2026-11-20T10:00:00Z")
    private val later = Instant.parse("2026-11-21T09:00:00Z")

    private fun read(at: Instant, vararg paths: String) =
        writer.importLocal(source, FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(1_000, 5) }, at = at)

    @Test
    fun `dado um capítulo que chega enquanto o Início é lido, então o Início sai inteiro de antes dele e a leitura seguinte o mostra`() {
        read(first, "Comics/Gantz/Cap. 001.cbz")
        writer.setReadingState(writer.works().single().id, ReadingState.READING)
        val reader = LibraryStore(WritesBetween(reading) { read(later, "Comics/Gantz/Cap. 001.cbz", "Comics/Gantz/Cap. 002.cbz") })

        val during = reader.home()

        assertEquals(emptyList(), during.arrived)
        assertEquals(listOf("Cap. 002"), reader.home().arrived.single().chapters.map { it.chapter.name })
    }
}
