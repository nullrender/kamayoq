package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.ImageMode
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.ImageModeSettings
import app.kamayoq.core.reader.ImageScope
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Slice 6 of the v1 interface: the image reader's mode, general and per work, and the hint of Pages ←.
class ImageLookStoreTest {
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private fun comic(path: String, title: String, subtype: ComicsSubtype?) = ImportedWork(
        path = path,
        title = title,
        type = WorkType.COMICS,
        subtype = subtype,
        libraryId = 1,
        chapters = listOf(ImportedChapter("1", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "1|10", 1, "Cap. 1.cbz", 1)),
        details = WorkDetails.NONE,
    )

    /** A manga and a manhwa from Kavita: the manga's subtype comes from its library's type. */
    private fun ids(): Pair<String, String> {
        store.importKavita(
            "server-1",
            listOf(comic("31", "Kaoru Hana wa Rin to Saku", ComicsSubtype.MANGA), comic("32", "Solo Leveling", ComicsSubtype.MANHWA)),
        )
        val works = store.works()
        return works.single { it.title == "Kaoru Hana wa Rin to Saku" }.id to works.single { it.title == "Solo Leveling" }.id
    }

    @Test
    fun `dado nenhuma escolha guardada, então o mangá abre em Páginas para a esquerda e o manhwa na Tira`() {
        val (manga, manhwa) = ids()

        assertEquals(ImageModeSettings(ImageModeChoice.AUTOMATIC, null, ImageMode.PAGES_RIGHT_TO_LEFT), store.imageMode(manga))
        assertEquals(ImageModeSettings(ImageModeChoice.AUTOMATIC, null, ImageMode.STRIP), store.imageMode(manhwa))
    }

    @Test
    fun `dado uma escolha só para o mangá, quando reabro, então ela volta e o manhwa não muda`() {
        val (manga, manhwa) = ids()

        store.saveImageMode(manga, store.imageMode(manga).choose(ImageScope.THIS_WORK, ImageModeChoice.PAGES_LEFT_TO_RIGHT))

        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, store.imageMode(manga).own)
        assertEquals(ImageMode.PAGES_LEFT_TO_RIGHT, store.imageMode(manga).effective)
        assertNull(store.imageMode(manhwa).own)
        assertEquals(ImageMode.STRIP, store.imageMode(manhwa).effective)
    }

    @Test
    fun `dado uma escolha para todas feita no mangá, então o manhwa também a segue`() {
        val (manga, manhwa) = ids()

        store.saveImageMode(manga, store.imageMode(manga).choose(ImageScope.ALL_IMAGE_WORKS, ImageModeChoice.PAGES_LEFT_TO_RIGHT))

        assertEquals(ImageModeChoice.PAGES_LEFT_TO_RIGHT, store.imageMode(manhwa).general)
        assertEquals(ImageMode.PAGES_LEFT_TO_RIGHT, store.imageMode(manhwa).effective)
    }

    @Test
    fun `dado o mangá com escolha própria, quando restauro só esta obra, então ela sai do banco`() {
        val (manga, _) = ids()
        store.saveImageMode(manga, store.imageMode(manga).choose(ImageScope.THIS_WORK, ImageModeChoice.STRIP))

        store.saveImageMode(manga, store.imageMode(manga).restore(ImageScope.THIS_WORK))

        assertNull(store.imageMode(manga).own)
        assertEquals(ImageMode.PAGES_RIGHT_TO_LEFT, store.imageMode(manga).effective)
    }

    @Test
    fun `dado Automático escolhido só para o mangá, quando reabro, então ele continua escolhido e não vira seguir todas`() {
        val (manga, manhwa) = ids()
        store.saveImageMode(manhwa, store.imageMode(manhwa).choose(ImageScope.ALL_IMAGE_WORKS, ImageModeChoice.STRIP))

        store.saveImageMode(manga, store.imageMode(manga).choose(ImageScope.THIS_WORK, ImageModeChoice.AUTOMATIC))

        assertEquals(ImageModeChoice.AUTOMATIC, store.imageMode(manga).own)
        assertEquals(ImageMode.PAGES_RIGHT_TO_LEFT, store.imageMode(manga).effective)
    }

    @Test
    fun `dado um nome de modo que esta versão não conhece, então ele vale como não escolhido`() {
        val (manga, _) = ids()
        store.saveImageMode(manga, store.imageMode(manga).choose(ImageScope.THIS_WORK, ImageModeChoice.STRIP).choose(ImageScope.ALL_IMAGE_WORKS, ImageModeChoice.STRIP))
        driver.execute(null, "UPDATE work_image_look SET mode = 'double-page'", 0)
        driver.execute(null, "UPDATE image_look_general SET mode = 'double-page'", 0)

        assertEquals(ImageModeSettings(ImageModeChoice.AUTOMATIC, null, ImageMode.PAGES_RIGHT_TO_LEFT), store.imageMode(manga))
    }

    @Test
    fun `dado a dica de Páginas para a esquerda, então ela começa não vista e fica vista depois de Entendi`() {
        assertFalse(store.rightToLeftHintSeen())

        store.markRightToLeftHintSeen()

        assertTrue(store.rightToLeftHintSeen())
    }

    @Test
    fun `dado a dica do leitor de texto vista, então a de Páginas para a esquerda continua não vista`() {
        store.markPagesHintSeen()

        assertFalse(store.rightToLeftHintSeen())
    }
}
