package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class ProgressSavingTest {

    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )

    @AfterTest
    fun close() = driver.close()

    @Test
    fun `dado uma gravação que o banco recusa, quando o leitor guarda a posição, então a falha volta como resultado e não fecha o leitor`() {
        val progress = ChapterProgress(
            readMark = null,
            position = StripPosition(3, 40, Instant.parse("2026-10-30T02:30:00Z"), "phone-a"),
            completeReads = 0,
        )

        // No chapter has this identifier: the database refuses the row, as a full disk would refuse it.
        val failure = ProgressSaving(LibraryStore(driver)).save("missing-chapter", progress)

        assertNotNull(failure)
    }
}
