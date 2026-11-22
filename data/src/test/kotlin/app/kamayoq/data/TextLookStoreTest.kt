package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkDetails
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReadingFont
import app.kamayoq.core.reader.TextEdit
import app.kamayoq.core.reader.TextNumber
import app.kamayoq.core.reader.TextScope
import app.kamayoq.core.reader.TextSettings
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Slice 5 of the v1 interface: the text reader's settings, general and per work, and each work's mode.
class TextLookStoreTest {
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { put("foreign_keys", "true") },
        LibraryStore.schema,
    )
    private val store = LibraryStore(driver)

    @AfterTest
    fun close() = driver.close()

    private fun novel(path: String, title: String) = ImportedWork(
        path = path,
        title = title,
        type = WorkType.NOVELS,
        subtype = null,
        libraryId = 3,
        chapters = listOf(ImportedChapter("1", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.EPUB, "1|10", 1, "Cap. 1.epub", 3)),
        details = WorkDetails.NONE,
    )

    private fun ids(): Pair<String, String> {
        store.importKavita("server-1", listOf(novel("21", "Escravo das Sombras"), novel("22", "Lorde dos Mistérios")))
        val works = store.works()
        return works.single { it.title == "Escravo das Sombras" }.id to works.single { it.title == "Lorde dos Mistérios" }.id
    }

    @Test
    fun `dado nenhum ajuste guardado, então a obra abre com os padrões e sem modo escolhido`() {
        val (shadow, _) = ids()
        assertEquals(TextSettings.DEFAULT, store.textSettings(shadow))
        assertNull(store.textMode(shadow))
    }

    @Test
    fun `dado ajustes gerais guardados, então cada obra de texto os lê`() {
        val (shadow, mysteries) = ids()
        val settings = TextSettings.DEFAULT
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.SIZE, 21))
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Theme(PageTheme.SEPIA))
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Justify(true))

        store.saveTextSettings(shadow, settings)

        assertEquals(settings, store.textSettings(shadow))
        assertEquals(settings, store.textSettings(mysteries))
    }

    @Test
    fun `dado ajustes próprios de uma obra, então só ela os tem e a outra segue os gerais`() {
        val (shadow, mysteries) = ids()
        val settings = TextSettings.DEFAULT
            .change(TextScope.ALL_TEXT_WORKS, TextEdit.Number(TextNumber.SIZE, 20))
            .change(TextScope.THIS_WORK, TextEdit.Font(ReadingFont.GARAMOND))
            .change(TextScope.THIS_WORK, TextEdit.Indent(false))
            .change(TextScope.THIS_WORK, TextEdit.Footer(FooterContent.NOTHING))

        store.saveTextSettings(shadow, settings)

        assertEquals(settings, store.textSettings(shadow))
        assertEquals(settings.copy(own = null), store.textSettings(mysteries))
    }

    @Test
    fun `dado Usar os gerais numa obra, quando guardo, então ela volta a não ter ajustes próprios`() {
        val (shadow, _) = ids()
        val own = TextSettings.DEFAULT.change(TextScope.THIS_WORK, TextEdit.Number(TextNumber.MARGIN, 32))
        store.saveTextSettings(shadow, own)

        store.saveTextSettings(shadow, own.restore(TextScope.THIS_WORK).first)

        assertNull(store.textSettings(shadow).own)
    }

    @Test
    fun `dado um modo escolhido para uma obra, então só ela o tem e restaurar os ajustes não o muda`() {
        val (shadow, mysteries) = ids()
        store.saveTextMode(shadow, ReaderFlow.PAGES)
        val own = TextSettings.DEFAULT.change(TextScope.THIS_WORK, TextEdit.Number(TextNumber.SIZE, 24))
        store.saveTextSettings(shadow, own)

        store.saveTextSettings(shadow, own.restore(TextScope.THIS_WORK).first)

        assertEquals(ReaderFlow.PAGES, store.textMode(shadow))
        assertNull(store.textMode(mysteries))
    }

    @Test
    fun `dado a dica das páginas, então ela aparece até a pessoa tocar em Entendi e depois nunca mais`() {
        assertFalse(store.pagesHintSeen())
        store.markPagesHintSeen()
        assertTrue(store.pagesHintSeen())
    }
}
