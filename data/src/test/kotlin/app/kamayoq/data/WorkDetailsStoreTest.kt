package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.Publication
import app.kamayoq.core.library.PublicationStatus
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.library.WorkType
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Slice 3 of the v1 interface: what Kavita says about a work, kept for the Work screen.
class WorkDetailsStoreTest {
    private val server = "server-1"
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private val gantz = WorkDetails(
        authors = listOf("Hiroya Oku"),
        summary = "Kei e Masaru morrem no metrô.\nAcordam num quarto com uma esfera preta.",
        tags = listOf("Ação", "Ficção científica"),
        publication = Publication(PublicationStatus.COMPLETED, 0),
    )

    private fun work(details: WorkDetails) = ImportedWork(
        path = "12",
        title = "Gantz",
        type = WorkType.COMICS,
        subtype = null,
        libraryId = 2,
        chapters = listOf(
            ImportedChapter("1", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "20|100", 20, "Cap. 1.cbz", 7),
        ),
        details = details,
    )

    private fun gantzId() = store.works().single { it.title == "Gantz" }.id

    @Test
    fun `dado uma obra do Kavita com autor, sinopse, etiquetas e situação, quando importo, então a Obra lê cada um como veio`() {
        store.importKavita(server, listOf(work(gantz)))

        assertEquals(gantz, store.details(gantzId()))
    }

    @Test
    fun `dado uma obra já importada, quando o Kavita muda a sinopse e a situação, então a próxima importação guarda as novas`() {
        store.importKavita(server, listOf(work(gantz)))
        val changed = gantz.copy(summary = "Outra sinopse.", publication = Publication(PublicationStatus.HIATUS, null))

        store.importKavita(server, listOf(work(changed)))

        assertEquals(changed, store.details(gantzId()))
    }

    @Test
    fun `dado a situação e o que falta desconhecidos, quando importo, então voltam desconhecidos, não zero`() {
        store.importKavita(server, listOf(work(gantz.copy(publication = Publication.UNKNOWN))))

        assertEquals(Publication.UNKNOWN, store.details(gantzId()).publication)
    }

    @Test
    fun `dado uma obra que nunca recebeu dados, então a Obra lê nenhum dado e nenhuma capa`() {
        store.importKavita(server, listOf(work(WorkDetails.NONE)))

        assertEquals(WorkDetails.NONE, store.details(gantzId()))
        assertNull(store.coverStamp(gantzId()))
    }

    @Test
    fun `dado a capa guardada, quando a importação seguinte traz dados novos, então a marca da capa continua`() {
        store.importKavita(server, listOf(work(gantz)))
        store.saveCover(gantzId(), "v12_c3.png")

        store.importKavita(server, listOf(work(gantz.copy(summary = "Outra sinopse."))))

        assertEquals("v12_c3.png", store.coverStamp(gantzId()))
    }

    @Test
    fun `dado a capa guardada, quando a fonte deixa de ter capa, então a marca volta a nenhuma`() {
        store.importKavita(server, listOf(work(gantz)))
        store.saveCover(gantzId(), "v12_c3.png")

        store.saveCover(gantzId(), null)

        assertNull(store.coverStamp(gantzId()))
    }
}
