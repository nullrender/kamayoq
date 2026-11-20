package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.progress.ReadingState
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

// Slice 2 of the v1 interface: a work's reading state, kept apart from the progress of its chapters.
class ReadingStateStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-state", ".db").also { it.delete() }
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    private fun open(): LibraryStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
        drivers += driver
        return LibraryStore(driver)
    }

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        file.delete()
    }

    private fun LibraryStore.import(vararg paths: String) =
        importLocal(source, FolderConvention.read(paths.toList()), paths.associateWith { FileStamp(1_000, 5) })

    private fun LibraryStore.work(title: String) = works().single { it.title == title }.id

    @Test
    fun `dado uma obra recém-chegada, então está sem estado e sem mudança automática`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")

        assertEquals(StoredReadingState.NONE, store.readingState(store.work("Sakamoto Days")))
    }

    @Test
    fun `dado Quero ler escolhido, quando o banco fecha e abre de novo, então a obra continua em Quero ler`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        store.setReadingState(store.work("Sakamoto Days"), ReadingState.WANT_TO_READ)

        drivers.forEach { it.close() }
        val reopened = open()

        assertEquals(StoredReadingState(ReadingState.WANT_TO_READ, automatic = null), reopened.readingState(reopened.work("Sakamoto Days")))
    }

    @Test
    fun `dado uma obra em Lendo, quando escolho deixar sem estado, então fica sem estado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz")
        val work = store.work("Sakamoto Days")
        store.setReadingState(work, ReadingState.READING)

        store.setReadingState(work, null)

        assertEquals(StoredReadingState.NONE, store.readingState(work))
    }

    @Test
    fun `dado duas obras, quando escolho o estado de uma, então a outra continua sem estado`() {
        val store = open()
        store.import("Comics/Sakamoto Days/Cap. 001.cbz", "Books/Dom Casmurro.epub")

        store.setReadingState(store.work("Sakamoto Days"), ReadingState.DROPPED)

        assertEquals(ReadingState.DROPPED, store.readingState(store.work("Sakamoto Days")).state)
        assertEquals(StoredReadingState.NONE, store.readingState(store.work("Dom Casmurro")))
    }

    @Test
    fun `dado o banco, quando um estado é gravado para uma obra que não existe, então o banco recusa`() {
        open()

        assertFails {
            drivers.last().execute(null, "INSERT INTO reading_state (work_id, state, automatic) VALUES ('work-nobody', 'READING', 0)", 0)
        }
    }
}
