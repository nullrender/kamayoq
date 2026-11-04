package app.kamayoq.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.data.db.KamayoqDatabase
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaMigrationTest {

    private val file: File = File.createTempFile("kamayoq-migration", ".db").also { it.delete() }
    private val driver: SqlDriver by lazy {
        File(SCHEMA_1).copyTo(file)
        JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") })
    }

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    /** A library as version 1 left it: the snapshot of that schema plus the frozen rows. */
    private fun version1(): SqlDriver {
        val rows = checkNotNull(javaClass.getResource("/migrations/schema-1-rows.sql")).readText()
        rows.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            .split(";\n").filter { it.isNotBlank() }
            .forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 1", 0)
        return driver
    }

    private fun migrated(): SqlDriver {
        version1()
        LibraryStore.schema.migrate(driver, 1, LibraryStore.schema.version)
        return driver
    }

    private fun rows(sql: String, columns: Int = 1): List<List<String?>> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val found = mutableListOf<List<String?>>()
            while (cursor.next().value) found += List(columns) { cursor.getString(it) }
            QueryResult.Value(found)
        },
        0,
    ).value

    private fun everyRowOfVersion1(): Map<String, List<List<String?>>> =
        VERSION_1_COLUMNS.mapValues { (table, columns) ->
            rows("SELECT ${columns.joinToString()} FROM $table ORDER BY rowid", columns.size)
        }

    private fun execute(sql: String) {
        driver.execute(null, sql, 0)
    }

    @Test
    fun `dado um banco da versão 1 com obras locais e progresso, quando migro para a 2, então cada obra, capítulo, posição e marca de lido continua igual`() {
        version1()
        val before = everyRowOfVersion1()
        assertEquals(listOf(2, 2, 6, 4, 2), before.values.map { it.size })

        LibraryStore.schema.migrate(driver, 1, LibraryStore.schema.version)

        assertEquals(2, LibraryStore.schema.version)
        assertEquals(before, everyRowOfVersion1())
    }

    @Test
    fun `dado um banco migrado, quando a biblioteca o abre, então mostra as obras e o progresso de antes`() {
        val store = LibraryStore(migrated())

        val work = store.works().single()

        assertEquals("Dom Casmurro", work.title)
        assertEquals(listOf("Capítulo 1", "Capítulo 2", "Volume 1"), store.chapters(work.id).map { it.name })
        assertEquals(12, store.progress("chapter-2").position?.page)
    }

    @Test
    fun `dado um banco migrado, quando leio as colunas novas das obras locais, então estão vazias`() {
        migrated()

        val filled = rows(
            "SELECT count(*) FROM chapter WHERE page_count IS NOT NULL OR remote_file IS NOT NULL " +
                "OR remote_volume_id IS NOT NULL",
        ).single().single()

        assertEquals("0", filled)
        assertEquals(
            "0",
            rows("SELECT count(*) FROM work_version WHERE remote_library_id IS NOT NULL").single().single(),
        )
    }

    @Test
    fun `dado o esquema da versão 2, quando procuro onde guardar a chave do Kavita, então nenhuma coluna serve`() {
        migrated()

        val columns = rows("SELECT name FROM sqlite_master WHERE type = 'table'")
            .flatMap { table -> rows("SELECT name FROM pragma_table_info('${table.single()}')") }
            .map { it.single().orEmpty() }

        assertTrue("address" in columns, "the listing must reach the new tables")
        assertTrue(columns.none { Regex("key|token|secret|password").containsMatchIn(it) }, columns.toString())
    }

    @Test
    fun `dado um acordo de progresso, quando ainda não há CFI, então ele fica nulo e pode vir depois`() {
        migrated()

        execute("INSERT INTO progress_agreement (chapter_id, page, own_reread) VALUES ('chapter-1', 3, 0)")
        assertNull(rows("SELECT local_cfi FROM progress_agreement").single().single())

        execute("UPDATE progress_agreement SET local_cfi = 'epubcfi(/6/4!/4/2)' WHERE chapter_id = 'chapter-1'")
        assertEquals("epubcfi(/6/4!/4/2)", rows("SELECT local_cfi FROM progress_agreement").single().single())
    }

    @Test
    fun `dado um acordo de progresso, quando os valores são inválidos, então o banco recusa`() {
        migrated()
        val insert = "INSERT INTO progress_agreement (chapter_id, page, own_reread) VALUES "

        execute("$insert ('chapter-1', 3, 1)")

        assertFails { execute("$insert ('chapter-2', -1, 0)") }
        assertFails { execute("$insert ('chapter-2', 3, 2)") }
        assertFails { execute("$insert ('no-chapter', 3, 0)") }
    }

    @Test
    fun `dado a fila de envio, quando o estado não é um dos dois, então o banco recusa`() {
        migrated()
        val insert = "INSERT INTO progress_outbox (chapter_id, page, state, queued_at) VALUES ('chapter-1', 3, "

        execute("$insert 'TO_SEND', 1761400200000)")
        execute("UPDATE progress_outbox SET state = 'SENT_UNCONFIRMED'")

        assertFails { execute("UPDATE progress_outbox SET state = 'SENT'") }
    }

    @Test
    fun `dado uma obra, quando guardo a posição não escolhida e a anterior, então as duas convivem`() {
        migrated()
        val insert = "INSERT INTO alternative_position (version_id, reason, chapter_id, page, total, at, device) VALUES "

        execute("$insert ('version-dom', 'CONFLICT', 'chapter-1', 3, 40, 1761400200000, 'kavita')")
        execute("$insert ('version-dom', 'PREVIOUS', 'chapter-2', 5, 30, 1761400200000, 'device-a')")

        assertEquals("2", rows("SELECT count(*) FROM alternative_position").single().single())
        assertFails { execute("$insert ('version-dom', 'CONFLICT', 'chapter-2', 1, 30, 1761400200000, 'kavita')") }
    }

    @Test
    fun `dado uma posição alternativa, quando o capítulo é de outra obra ou a página passa do total, então o banco recusa`() {
        migrated()
        val insert = "INSERT INTO alternative_position (version_id, reason, chapter_id, page, total, at, device) VALUES "

        execute("$insert ('version-sakamoto', 'CONFLICT', 'chapter-gone', 40, 40, 1761400200000, 'kavita')")

        assertFails { execute("$insert ('version-dom', 'CONFLICT', 'chapter-gone', 3, 40, 1761400200000, 'kavita')") }
        assertFails { execute("$insert ('version-dom', 'CONFLICT', 'chapter-1', 41, 40, 1761400200000, 'kavita')") }
        assertFails { execute("$insert ('version-dom', 'CONFLICT', 'chapter-1', 0, 0, 1761400200000, 'kavita')") }
    }

    @Test
    fun `dado uma biblioteca marcada, quando o servidor não existe, então o banco recusa`() {
        migrated()

        execute("INSERT INTO kavita_server (id, address) VALUES ('server-1', 'https://kavita.example')")
        execute("INSERT INTO kavita_library (server_id, library_id) VALUES ('server-1', 1)")

        assertFails { execute("INSERT INTO kavita_library (server_id, library_id) VALUES ('server-2', 1)") }
    }

    @Test
    fun `dado um servidor com obra, quando o endereço muda, então a obra continua ligada ao mesmo servidor`() {
        migrated()
        execute("INSERT INTO kavita_server (id, address) VALUES ('server-1', 'https://kavita.example')")
        execute(
            "INSERT INTO work_version (id, work_id, origin, source, path, present, sort_index, remote_library_id) " +
                "VALUES ('version-kavita', 'work-dom', 'kavita', 'server-1', '77', 1, 2, 4)",
        )

        execute("UPDATE kavita_server SET address = 'https://kavita-novo.example' WHERE id = 'server-1'")

        assertEquals(
            listOf("server-1", "4"),
            rows(
                "SELECT source, remote_library_id FROM work_version " +
                    "JOIN kavita_server ON kavita_server.id = work_version.source",
                columns = 2,
            ).single(),
        )
    }

    @Test
    fun `dado um capítulo do Kavita, quando o mesmo id volta a ser importado, então o banco recusa a segunda linha`() {
        migrated()
        val insert = "INSERT INTO chapter (id, version_id, path, name, kind, format, sort_index, present, stamp, " +
            "needs_check, page_count, remote_file, remote_volume_id) VALUES "

        execute("$insert ('kavita-a', 'version-dom', '1234', 'Cap', 'CHAPTER', 'ARCHIVE', 9, 1, 's', 0, 20, 'a.cbz', 8)")

        assertFails {
            execute("$insert ('kavita-b', 'version-dom', '1234', 'Cap', 'CHAPTER', 'ARCHIVE', 9, 1, 's', 0, 20, 'a.cbz', 8)")
        }
    }

    @Test
    fun `dado um capítulo do Kavita, quando o número de páginas é negativo, então o banco recusa`() {
        migrated()

        execute("UPDATE chapter SET page_count = 0 WHERE id = 'chapter-1'")

        assertFails { execute("UPDATE chapter SET page_count = -1 WHERE id = 'chapter-1'") }
    }

    @Test
    fun `dado um capítulo do Kavita, quando vira edição nova, então ela herda páginas, arquivo e volume`() {
        migrated()
        execute("UPDATE chapter SET page_count = 20, remote_file = 'a.cbz', remote_volume_id = 8 WHERE id = 'chapter-1'")

        val chapters = KamayoqDatabase(driver).chapterQueries
        chapters.transaction {
            chapters.replace("chapter-1-next", "chapter-1")
            chapters.insertEdition("chapter-1-next", 1, "hash-next", "chapter-1")
        }

        assertEquals(
            listOf("20", "a.cbz", "8"),
            rows("SELECT page_count, remote_file, remote_volume_id FROM chapter WHERE id = 'chapter-1-next'", columns = 3)
                .single(),
        )
    }

    private companion object {
        const val SCHEMA_1 = "src/main/sqldelight/databases/1.db"

        val VERSION_1_COLUMNS = linkedMapOf(
            "work" to listOf("id", "title", "alternative_title", "type", "subtype"),
            "work_version" to listOf("id", "work_id", "origin", "source", "path", "present", "sort_index"),
            "chapter" to listOf(
                "id", "version_id", "path", "name", "kind", "number", "format", "sort_index", "present", "stamp",
                "content_hash", "needs_check", "replaced_by",
            ),
            "chapter_progress" to listOf(
                "chapter_id", "read_at", "read_source", "position_page", "position_total", "position_at",
                "position_device", "complete_reads",
            ),
            "work_resume" to listOf("version_id", "chapter_id"),
        )
    }
}
