package app.kamayoq.data.kavita

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Kavita 0.9.1 does to books and to whole series, which rule 8b stands on. As in
 * [KavitaFactsTest], no honest red commit: docs/decisions/kavita-step-3-2026-10-30.md, decision 4.
 */
class KavitaReturnFactsTest {
    private val mutableFolder = File(
        System.getenv("KAVITA_FIXTURES") ?: error("KAVITA_FIXTURES is not set: this suite changes those files"),
    )
    private val books = File(mutableFolder.parentFile, "books")
    private val noNumber = "-100000"

    private fun comic(name: String) = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == name }

    private fun bookOrNull(name: String) = KavitaServer.works(KavitaServer.bookLibraryId).singleOrNull { it.name == name }

    private fun book(name: String) = checkNotNull(bookOrNull(name)) { "No book $name" }

    private fun KavitaWork.chapters() = KavitaServer.chapters(id)

    // Dates the change later than the scan saw, up to the library's folder: see KavitaFactsTest.
    private fun changed(vararg files: File, root: File) {
        val at = System.currentTimeMillis() + 120_000
        (files.toList() + root).forEach { check(it.setLastModified(at)) }
    }

    @Test
    fun `dado um EPUB sem série, quando o Kavita o varre, então ele fica sem número, como especial`() {
        val chapter = book("Lone Book").chapters().single()

        assertEquals(noNumber, chapter.number)
        assertTrue(chapter.isSpecial)
        assertEquals("Lone Book.epub", chapter.files.single().name)
    }

    @Test
    fun `dado três EPUBs iguais em título e páginas, quando dois somem e voltam, então voltam com outros ids e os mesmos nomes de arquivo`() {
        val folder = File(books, "Same Title")
        val before = book("Same Title").chapters()
        assertEquals(setOf(noNumber), before.map { it.number }.toSet())
        assertEquals(setOf(3), before.map { it.pages }.toSet())

        for (name in listOf("A file", "B file")) check(File(folder, "$name.epub").delete())
        changed(folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)
        assertEquals(listOf("C file.epub"), book("Same Title").chapters().map { it.files.single().name })

        for (name in listOf("A file", "B file")) writeEpub(File(folder, "$name.epub"), "Same Title")
        changed(File(folder, "A file.epub"), File(folder, "B file.epub"), folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)

        val after = book("Same Title").chapters()
        val idOf = { chapters: List<KavitaChapter>, name: String -> chapters.single { it.files.single().name == name }.id }
        assertNotEquals(idOf(before, "A file.epub"), idOf(after, "A file.epub"))
        assertNotEquals(idOf(before, "B file.epub"), idOf(after, "B file.epub"))
        assertEquals(idOf(before, "C file.epub"), idOf(after, "C file.epub"))
    }

    @Test
    fun `dado um EPUB renomeado, quando a varredura termina, então ele ganha outro id`() {
        val folder = File(books, "Same Title")
        val before = book("Same Title").chapters().single { it.files.single().name == "C file.epub" }

        val renamed = File(folder, "C renamed.epub")
        check(File(folder, "C file.epub").renameTo(renamed))
        changed(renamed, folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)

        val after = book("Same Title").chapters().single { it.files.single().name == "C renamed.epub" }
        assertNotEquals(before.id, after.id)
        check(renamed.renameTo(File(folder, "C file.epub")))
        changed(File(folder, "C file.epub"), folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)
    }

    @Test
    fun `dado um CBZ renomeado, quando a varredura termina, então ele mantém o id`() {
        val work = comic("Renamed Work")
        val before = work.chapters().single()
        val folder = File(mutableFolder, "Renamed Work")

        val renamed = File(folder, "Renamed Work Chapter 1 renamed.cbz")
        check(File(folder, "Renamed Work Ch. 001.cbz").renameTo(renamed))
        changed(renamed, folder, root = mutableFolder)
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val after = work.chapters().single()
        assertEquals(before.id, after.id)
        assertEquals("Renamed Work Chapter 1 renamed.cbz", after.files.single().name)
    }

    @Test
    fun `dado um livro sozinho na série, quando o arquivo some e volta, então a série volta com outro id`() {
        val before = book("Lone Book")
        val folder = File(books, "Lone Book")
        val file = File(folder, "Lone Book.epub")

        check(file.delete())
        changed(folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)
        assertNull(bookOrNull("Lone Book"))

        writeEpub(file, "Lone Book")
        changed(file, folder, root = books)
        KavitaServer.rescan(KavitaServer.bookLibraryId)

        assertNotEquals(before.id, book("Lone Book").id)
    }

    @Test
    fun `dado uma série inteira que some e volta, então série, volume e capítulos voltam com outros ids`() {
        val work = comic("Returning Work")
        val before = work.chapters()
        val folder = File(mutableFolder, "Returning Work")

        check(folder.deleteRecursively())
        changed(root = mutableFolder)
        KavitaServer.rescan(KavitaServer.mutableLibraryId)
        assertTrue(KavitaServer.works(KavitaServer.mutableLibraryId).none { it.name == "Returning Work" })

        writeChapter(mutableFolder, "Returning Work", number = 1, pages = 3)
        writeChapter(mutableFolder, "Returning Work", number = 2, pages = 3)
        changed(*folder.listFiles().orEmpty(), folder, root = mutableFolder)
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val back = comic("Returning Work")
        assertNotEquals(work.id, back.id)
        val after = back.chapters()
        assertTrue(before.map { it.id }.intersect(after.map { it.id }.toSet()).isEmpty())
        assertNotEquals(before.first().volumeId, after.first().volumeId)
    }

    @Test
    fun `dado uma varredura sem mudança, então o volume mantém o id`() {
        val work = comic("Stable Work")
        val before = work.chapters().single().volumeId

        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        assertEquals(before, work.chapters().single().volumeId)
    }

    @Test
    fun `dado um arquivo trocado com as mesmas páginas, então o total de páginas não muda e o tamanho muda`() {
        val work = comic("Swapped Work")
        val before = work.chapters().single()
        val file = File(mutableFolder, "Swapped Work/Swapped Work Ch. 001.cbz")

        writeComic(file, pages = 3, extra = "x".repeat(500))
        changed(file, file.parentFile, root = mutableFolder)
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val after = work.chapters().single()
        assertEquals(before.id, after.id)
        assertEquals(before.pages, after.pages)
        assertNotEquals(before.files.single().bytes, after.files.single().bytes)
    }

    @Test
    fun `dado um capítulo em intervalo, então number traz só o início e maxNumber o fim`() {
        val chapter = comic("Ranged Work").chapters().single()

        assertEquals("1", chapter.number)
        assertEquals(3.0, chapter.maxNumber)
    }

    @Test
    fun `dado dois arquivos do mesmo capítulo, então o capítulo soma as páginas dos dois`() {
        val chapter = comic("Two Files Work").chapters().single()

        assertEquals(7, chapter.pages)
        assertEquals(
            listOf("Two Files Work Ch. 001 extra.cbz", "Two Files Work Ch. 001.cbz"),
            chapter.files.map { it.name }.sorted(),
        )
    }

    @Test
    fun `dado volumes, soltos e especial, então a lista vem com os soltos primeiro e o especial por último`() {
        val work = comic("Mixed Work")

        assertEquals(listOf(-100000.0, 1.0, 2.0, 100000.0), KavitaServer.volumeNumbers(work.id))
        assertFalse(work.chapters().first().isSpecial)
        assertTrue(work.chapters().last().isSpecial)
    }
}
