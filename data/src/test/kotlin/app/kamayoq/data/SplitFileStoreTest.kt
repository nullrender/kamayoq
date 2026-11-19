package app.kamayoq.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.Work
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.reader.BookContents
import app.kamayoq.core.reader.ContentsChapter
import app.kamayoq.core.reader.ReaderChapters
import java.io.File
import java.time.Instant
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Step 4, milestone B: a compiled novel is one file with several chapters, each with its own
// identity and reading (docs/decisions/novel-chapters-2026-11-18.md).
class SplitFileStoreTest {

    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-split", ".db").also { it.delete() }
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private val novel = "Novels/Release That Witch/Release That Witch.epub"

    private fun chapter(number: Int, key: String = "OEBPS/c$number.xhtml") =
        ReadingUnit(novel, "Capítulo $number", UnitKind.CHAPTER, number.toDouble(), UnitContent.SplitEpub, tocKey = key)

    private val preface = ReadingUnit(novel, "Prefácio", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = "OEBPS/p.xhtml")

    private val readMark = ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END)

    private val changed = FileStamp(1_200, 1_761_500_000_000)

    private fun workId() = store.works().single().id

    private fun import(vararg units: ReadingUnit, stamp: FileStamp = FileStamp(1_000, 1_761_400_000_000), folder: String = "Novels/Release That Witch") {
        // The same units under another spelling of the work's folder, when [folder] says so.
        val placed = units.map { it.copy(path = folder + it.path.removePrefix("Novels/Release That Witch")) }
        val work = Work(folder, "Release That Witch", null, WorkType.NOVELS, null, placed)
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), placed.map { it.path }.distinct().associateWith { stamp })
    }

    private fun chapters() = store.chapters(store.works().single().id)

    private fun reading(fraction: Int) = ChapterProgress(
        readMark = null,
        position = TextPosition(0, 3, "epubcfi(/6/2!/4/$fraction)", BasisPoints.fromStored(fraction), Instant.parse("2026-11-18T12:00:00Z"), "phone-a"),
        completeReads = 0,
    )

    @Test
    fun `dado três capítulos do mesmo arquivo, quando importo, então são três capítulos na ordem, cada um com a sua chave`() {
        import(chapter(1), chapter(2), chapter(3))

        assertEquals(listOf("Capítulo 1", "Capítulo 2", "Capítulo 3"), chapters().map { it.name })
        assertEquals(listOf(novel, novel, novel), chapters().map { it.path })
        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml", "OEBPS/c3.xhtml"), chapters().map { it.tocKey })
        assertEquals(3, chapters().map { it.id }.toSet().size)
    }

    @Test
    fun `dado dois capítulos do mesmo arquivo lidos pela metade, quando importo de novo, então cada um mantém o identificador e o seu lugar`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()
        store.saveProgress(first.id, reading(2_500))
        store.saveProgress(second.id, reading(7_000))

        import(chapter(1), chapter(2))

        assertEquals(listOf(first.id, second.id), chapters().map { it.id })
        assertEquals(2_500, (store.progress(first.id).position as TextPosition).fraction.value)
        assertEquals(7_000, (store.progress(second.id).position as TextPosition).fraction.value)
    }

    @Test
    fun `dado um capítulo do arquivo marcado como lido, então só ele fica lido e a obra reabre nele`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()

        store.saveProgress(second.id, reading(10_000).copy(readMark = ReadMark(Instant.parse("2026-11-18T12:00:00Z"), ReadMarkSource.READ_TO_END)))

        assertEquals(null, store.progress(first.id).readMark)
        assertEquals(ReadMarkSource.READ_TO_END, store.progress(second.id).readMark?.source)
        assertEquals(second.id, store.resumeChapter(store.works().single().id)?.id)
    }

    @Test
    fun `dado um livro inteiro e um arquivo dividido, então o livro tem a chave vazia`() {
        val book = ReadingUnit("Books/Dom Casmurro.epub", "Dom Casmurro", UnitKind.VOLUME, null, UnitContent.Epub)
        val work = Work("Books/Dom Casmurro.epub", "Dom Casmurro", null, WorkType.BOOKS, null, listOf(book))
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(book.path to FileStamp(1, 1)))

        assertEquals(listOf(""), chapters().map { it.tocKey })
    }

    // Rule E1 for a split file: the whole file is compared, by the titles of its contents in order.

    @Test
    fun `dado uma edição com os mesmos títulos na mesma ordem e outros destinos, quando importo, então cada capítulo mantém o identificador e o progresso e ganha a chave nova`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()
        store.saveProgress(first.id, reading(10_000).copy(readMark = readMark))
        store.saveProgress(second.id, reading(3_000))

        import(chapter(1, key = "Text/a.xhtml"), chapter(2, key = "Text/b.xhtml"), stamp = changed)

        assertEquals(listOf(first.id, second.id), chapters().map { it.id })
        assertEquals(listOf("Text/a.xhtml", "Text/b.xhtml"), chapters().map { it.tocKey })
        assertEquals(readMark, store.progress(first.id).readMark)
        assertEquals(3_000, (store.progress(second.id).position as TextPosition).fraction.value)
        assertEquals(emptyList(), store.editionOffers(workId()))
    }

    @Test
    fun `dado um EPUB trocado por uma edição com um prefácio novo, quando importo, então as marcas não passam sozinhas e a obra pergunta`() {
        import(chapter(1), chapter(2), chapter(3))
        val old = chapters()
        store.saveProgress(old[0].id, reading(10_000).copy(readMark = readMark))
        store.saveProgress(old[1].id, reading(10_000).copy(readMark = readMark))

        import(preface, chapter(1), chapter(2), chapter(3), stamp = changed)

        val now = chapters()
        assertEquals(listOf("Prefácio", "Capítulo 1", "Capítulo 2", "Capítulo 3"), now.map { it.name })
        assertEquals(emptySet(), now.map { it.id }.toSet() intersect old.map { it.id }.toSet())
        assertEquals(List(4) { ChapterProgress.NEVER_OPENED }, now.map { store.progress(it.id) })
        // The previous edition keeps its reading, out of the list.
        assertEquals(readMark, store.progress(old[1].id).readMark)
        assertEquals(listOf(EditionOffer(novel, lastRead = "Capítulo 2")), store.editionOffers(workId()))
    }

    @Test
    fun `dado a pergunta do arquivo que mudou, quando aplico à nova edição, então os capítulos de mesmo título ficam lidos e a pergunta some`() {
        import(chapter(1), chapter(2), chapter(3))
        val old = chapters()
        store.saveProgress(old[0].id, reading(10_000).copy(readMark = readMark))
        store.saveProgress(old[1].id, reading(4_000))
        import(preface, chapter(1), chapter(2), chapter(3), stamp = changed)

        store.applyPreviousEdition(workId(), novel)

        val now = chapters()
        assertEquals(listOf(null, readMark, null, null), now.map { store.progress(it.id).readMark })
        // A place does not carry over: it points into the other edition's text.
        assertEquals(listOf(null, null, null, null), now.map { store.progress(it.id).position })
        assertEquals(emptyList(), store.editionOffers(workId()))
    }

    @Test
    fun `dado a pergunta do arquivo que mudou, quando não aplico, então nada passa e a pergunta some`() {
        import(chapter(1), chapter(2))
        store.saveProgress(chapters()[0].id, reading(10_000).copy(readMark = readMark))
        import(preface, chapter(1), chapter(2), stamp = changed)

        store.dismissEdition(workId(), novel)

        assertEquals(List(3) { ChapterProgress.NEVER_OPENED }, chapters().map { store.progress(it.id) })
        assertEquals(emptyList(), store.editionOffers(workId()))
    }

    @Test
    fun `dado uma edição nova de um arquivo nunca lido, quando importo, então nada é perguntado`() {
        import(chapter(1), chapter(2))

        import(preface, chapter(1), chapter(2), stamp = changed)

        assertEquals(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), chapters().map { it.name })
        assertEquals(emptyList(), store.editionOffers(workId()))
    }

    @Test
    fun `dado um capítulo de arquivo dividido que abre com outro conteúdo, então ele continua o mesmo e o arquivo inteiro fica conferido`() {
        import(chapter(1), chapter(2))
        val (first, second) = chapters()
        store.confirmContent(first.id, "hash-a")
        store.saveProgress(second.id, reading(3_000))

        // The same titles in the same order: the text may have changed, the chapters are the same.
        val opened = store.confirmContent(second.id, "hash-b")

        assertEquals(second.id, opened)
        assertEquals(listOf(first.id, second.id), chapters().map { it.id })
        assertEquals(listOf(false, false), chapters().map { it.needsContentCheck })
    }

    @Test
    fun `dado os capítulos de um arquivo dividido, quando a leitura da pasta pergunta, então recebe o carimbo e os capítulos guardados, na ordem`() {
        import(chapter(1), chapter(2))

        val known = store.splitFiles(source)

        assertEquals(setOf(novel), known.keys)
        assertEquals("file:1000:1761400000000", known.getValue(novel).stamp)
        assertEquals(
            listOf(ContentsChapter("Capítulo 1", "OEBPS/c1.xhtml", null), ContentsChapter("Capítulo 2", "OEBPS/c2.xhtml", null)),
            known.getValue(novel).chapters,
        )
    }

    @Test
    fun `dado um capítulo de um arquivo dividido, então os capítulos do mesmo arquivo vêm na ordem do sumário`() {
        import(chapter(1), chapter(2), chapter(3))
        val all = chapters()

        assertEquals(all.map { it.id }, store.fileChapters(all[1].id).map { it.id })
    }

    @Test
    fun `dado o sumário lido na abertura diferente do guardado, quando o leitor refaz a divisão, então vale a regra do arquivo inteiro`() {
        import(chapter(1), chapter(2))
        val old = chapters()
        store.saveProgress(old[0].id, reading(10_000).copy(readMark = readMark))

        val current = store.resplit(
            old[1].id,
            listOf(ContentsChapter("Prefácio", "OEBPS/p.xhtml", null), ContentsChapter("Capítulo 1", "OEBPS/c1.xhtml", null), ContentsChapter("Capítulo 2", "OEBPS/c2.xhtml", null)),
        )

        assertEquals(listOf("Prefácio", "Capítulo 1", "Capítulo 2"), chapters().map { it.name })
        // The chapter asked for is now the one at the same destination in the new edition.
        assertEquals(chapters()[2].id, current)
        assertEquals(listOf(EditionOffer(novel, lastRead = "Capítulo 1")), store.editionOffers(workId()))
    }

    // Found by the independent review of 18/11/2026, before the code reached the CI.

    private val renamed = "Novels/release that witch"

    @Test
    fun `dado a pasta da obra renomeada só na caixa das letras junto com a edição nova, quando importo, então a pergunta ainda acha o que foi lido`() {
        import(chapter(1), chapter(2))
        store.saveProgress(chapters()[0].id, reading(10_000).copy(readMark = readMark))

        import(preface, chapter(1), chapter(2), stamp = changed, folder = renamed)

        val path = "$renamed/Release That Witch.epub"
        assertEquals(listOf(EditionOffer(path, lastRead = "Capítulo 1")), store.editionOffers(workId()))
        store.applyPreviousEdition(workId(), path)
        assertEquals(listOf(null, readMark, null), chapters().map { store.progress(it.id).readMark })
    }

    @Test
    fun `dado a pergunta ainda aberta, quando a pasta da obra é renomeada só na caixa das letras, então a pergunta acompanha o arquivo`() {
        import(chapter(1), chapter(2))
        store.saveProgress(chapters()[0].id, reading(10_000).copy(readMark = readMark))
        import(preface, chapter(1), chapter(2), stamp = changed)

        import(preface, chapter(1), chapter(2), stamp = changed, folder = renamed)

        val path = "$renamed/Release That Witch.epub"
        assertEquals(listOf(EditionOffer(path, lastRead = "Capítulo 1")), store.editionOffers(workId()))
        store.applyPreviousEdition(workId(), path)
        assertEquals(listOf(null, readMark, null), chapters().map { store.progress(it.id).readMark })
    }

    @Test
    fun `dado a pergunta ainda aberta, quando o arquivo muda de edição outra vez, então a pergunta continua valendo para o que foi lido`() {
        import(chapter(1), chapter(2))
        store.saveProgress(chapters()[0].id, reading(10_000).copy(readMark = readMark))
        import(preface, chapter(1), chapter(2), stamp = changed)

        import(preface, chapter(1), chapter(2), chapter(3), stamp = FileStamp(1_300, 1_761_600_000_000))

        assertEquals(listOf(EditionOffer(novel, lastRead = "Capítulo 1")), store.editionOffers(workId()))
        store.applyPreviousEdition(workId(), novel)
        assertEquals(listOf(null, readMark, null, null), chapters().map { store.progress(it.id).readMark })
    }

    @Test
    fun `dado um arquivo dividido e dois arquivos novos cujo nome só difere dele na caixa das letras, quando importo, então cada arquivo fica com os seus capítulos`() {
        import(chapter(1), chapter(2))
        val upper = "Novels/Release That Witch/RELEASE THAT WITCH.epub"
        val lower = "Novels/Release That Witch/release that witch.epub"
        val units = listOf(upper, lower).flatMap { path -> listOf(chapter(1).copy(path = path), chapter(2).copy(path = path)) }
        val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units)

        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(upper to changed, lower to changed))

        assertEquals(listOf(upper, upper, lower, lower), chapters().map { it.path })
        assertEquals(4, chapters().map { it.id }.toSet().size)
    }

    @Test
    fun `dado um arquivo sem sumário lido pela metade, quando ele é renomeado só na caixa das letras, então continua o mesmo capítulo, com o seu lugar`() {
        fun whole(file: String) = ReadingUnit("Novels/Release That Witch/$file.epub", file, UnitKind.VOLUME, 1.0, UnitContent.SplitEpub)
        fun importWhole(file: String) {
            val unit = whole(file)
            val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, listOf(unit))
            store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(unit.path to changed))
        }
        importWhole("vol 1")
        val before = chapters().single()
        store.saveProgress(before.id, reading(4_000))

        importWhole("Vol 1")

        assertEquals(listOf(before.id), chapters().map { it.id })
        assertEquals("Vol 1", chapters().single().name)
        assertEquals(4_000, (store.progress(before.id).position as TextPosition).fraction.value)
        assertEquals(emptyList(), store.editionOffers(workId()))
    }

    @Test
    fun `dado dois capítulos do mesmo arquivo, quando faço a cópia de segurança, então cada um leva a sua chave`() {
        import(chapter(1), chapter(2))

        val backup = store.backup("r480", Instant.parse("2026-11-18T22:30:00Z"))

        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml"), backup.works.single().chapters.map { it.tocKey })
    }
}

// Step 4, piece B7: what the reader gets to read a file across its chapters.
class SplitOpeningTest {
    private val source = "content://com.android.externalstorage.documents/tree/primary%3AKamayoq"
    private val file: File = File.createTempFile("kamayoq-split-opening", ".db").also { it.delete() }
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("foreign_keys", "true") }, LibraryStore.schema)
    private val store = LibraryStore(driver)
    private val novel = "Novels/Release That Witch/Release That Witch.epub"

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private fun contents(vararg keys: String) = BookContents(
        entries = emptyList(),
        chapters = keys.mapIndexed { index, key -> ContentsChapter("Capítulo ${index + 1}", key, volume = null) },
        // A volume's title page first, leading into the first chapter, then one item per chapter.
        chapterOfItem = listOf(0) + keys.indices,
    )

    private fun import(vararg keys: String) {
        val units = keys.mapIndexed { index, key ->
            ReadingUnit(novel, if (key.isEmpty()) "Release That Witch" else "Capítulo ${index + 1}", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = key)
        }
        val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units)
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(novel to FileStamp(1_000, 1_761_400_000_000)))
    }

    private fun chapters() = store.chapters(store.works().single().id)

    private val halfway = ChapterProgress(
        readMark = null,
        position = TextPosition(1, 3, "epubcfi(/6/4!/4/8)", BasisPoints.fromStored(4_000), Instant.parse("2026-11-18T12:00:00Z"), "phone-a"),
        completeReads = 0,
    )

    @Test
    fun `dado o segundo capítulo de um arquivo dividido, quando o leitor o abre, então recebe os capítulos do arquivo, o lugar de cada um e qual foi aberto`() {
        import("c1.xhtml", "c2.xhtml", "c3.xhtml")
        val rows = chapters()
        store.saveProgress(rows[2].id, halfway)

        val opening = store.splitOpening(rows[1].id, contents("c1.xhtml", "c2.xhtml", "c3.xhtml")) as? SplitOpening

        assertEquals(rows.map { it.id }, opening?.chapterIds)
        assertEquals(1, opening?.opened)
        assertEquals(listOf(ChapterProgress.NEVER_OPENED, ChapterProgress.NEVER_OPENED, halfway), opening?.saved)
        assertEquals(ReaderChapters(listOf("c1.xhtml", "c2.xhtml", "c3.xhtml"), ofItem = listOf(0, 0, 1, 2)), opening?.chapters)
    }

    @Test
    fun `dado um arquivo cujo sumário mudou desde a leitura da pasta, quando o leitor o abre, então os capítulos passam a ser os do arquivo`() {
        import("c1.xhtml", "c2.xhtml")
        val asked = chapters()[1].id

        val opening = store.splitOpening(asked, contents("p.xhtml", "c1.xhtml", "c2.xhtml")) as? SplitOpening

        assertEquals(listOf("p.xhtml", "c1.xhtml", "c2.xhtml"), chapters().map { it.tocKey })
        assertEquals(chapters().map { it.id }, opening?.chapterIds)
        // The chapter asked for is the one at the same destination now.
        assertEquals(2, opening?.opened)
    }

    @Test
    fun `dado um arquivo guardado inteiro que agora traz sumário, quando o leitor o abre, então ele passa a ter os capítulos`() {
        import("")

        val opening = store.splitOpening(chapters().single().id, contents("c1.xhtml", "c2.xhtml")) as? SplitOpening

        assertEquals(listOf("c1.xhtml", "c2.xhtml"), chapters().map { it.tocKey })
        assertEquals(0, opening?.opened)
    }

    @Test
    fun `dado um arquivo que não traz a divisão, quando o leitor o abre, então ele é lido inteiro`() {
        import("")

        val whole = chapters().single().id

        assertEquals(whole, (store.splitOpening(whole, BookContents.UNDIVIDED) as? WholeOpening)?.chapterId)
        assertEquals(listOf(""), chapters().map { it.tocKey })
    }

    // Found by the Gemini review of milestone B: the reader took the name and the next chapter from the
    // chapters as they were before the file was read, so the end block offered a chapter replaced as
    // it opened, and the title named it.
    @Test
    fun `dado um arquivo guardado dividido seguido de outro arquivo, quando ele abre sem sumário, então a leitura inteira tem o nome dele e o próximo é o primeiro capítulo do arquivo seguinte`() {
        val second = "Novels/Release That Witch/Vol. 2.epub"
        val units = listOf(
            ReadingUnit(novel, "Capítulo 1", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = "c1.xhtml"),
            ReadingUnit(novel, "Capítulo 2", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = "c2.xhtml"),
            ReadingUnit(second, "Capítulo 3", UnitKind.CHAPTER, null, UnitContent.SplitEpub, tocKey = "v2.xhtml#c3"),
        )
        val work = Work("Novels/Release That Witch", "Release That Witch", null, WorkType.NOVELS, null, units)
        val stamp = FileStamp(1_000, 1_761_400_000_000)
        store.importLocal(source, LocalLibrary(listOf(work), emptyList()), mapOf(novel to stamp, second to stamp))

        val opening = store.splitOpening(chapters()[0].id, BookContents.UNDIVIDED) as? WholeOpening

        assertEquals("Release That Witch", opening?.name)
        assertEquals(chapters().single { it.path == second }.id, opening?.next?.id)
    }

    // Found by the Gemini review of milestone B: the opening compared only the destinations, so an
    // edition with the same destinations and other titles was read under the chapters of the old one.
    @Test
    fun `dado um arquivo com outros títulos nos mesmos destinos desde a leitura da pasta, quando o leitor o abre, então os capítulos passam a ser os do arquivo, numa edição nova`() {
        import("c1.xhtml", "c2.xhtml")
        val before = chapters().map { it.id }
        val retitled = BookContents(
            entries = emptyList(),
            chapters = listOf(ContentsChapter("Prefácio", "c1.xhtml", volume = null), ContentsChapter("Capítulo 1", "c2.xhtml", volume = null)),
            chapterOfItem = listOf(0, 0, 1),
        )

        val opening = store.splitOpening(before[1], retitled) as? SplitOpening

        assertEquals(listOf("Prefácio", "Capítulo 1"), chapters().map { it.name })
        assertEquals(chapters().map { it.id }, opening?.chapterIds)
        assertEquals(emptyList(), opening?.chapterIds.orEmpty().filter { it in before })
    }

    // Found by the independent review of 18/11/2026: the reader went on saving into the chapter the
    // file no longer had.
    @Test
    fun `dado um arquivo guardado dividido que agora não traz sumário, quando o leitor o abre, então o capítulo a ler é o inteiro que tomou o lugar dele`() {
        import("c1.xhtml", "c2.xhtml")
        val asked = chapters()[1].id

        val opening = store.splitOpening(asked, BookContents.UNDIVIDED) as? WholeOpening

        assertEquals(listOf(""), chapters().map { it.tocKey })
        assertEquals(chapters().single().id, opening?.chapterId)
    }
}
