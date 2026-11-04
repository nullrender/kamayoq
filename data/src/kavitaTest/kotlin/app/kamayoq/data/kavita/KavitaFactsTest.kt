package app.kamayoq.data.kavita

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * What Kavita 0.9.1 does today, so a new version that changes it turns CI red. These tests have no
 * honest red commit: docs/decisions/kavita-step-3-2026-10-30.md, decision 4.
 */
class KavitaFactsTest {
    private val mutableFolder = File(
        System.getenv("KAVITA_FIXTURES") ?: error("KAVITA_FIXTURES is not set: this suite changes those files"),
    )

    private fun work(name: String) = KavitaServer.works().single { it.name == name }

    private fun mutableWork(name: String) = KavitaServer.works(KavitaServer.mutableLibraryId).single { it.name == name }

    private fun KavitaWork.chapters() = KavitaServer.chapters(id)

    private fun chapterFile(work: String, number: Int) =
        File(mutableFolder, "$work/$work Ch. %03d.cbz".format(number))

    // The scanner ignores a file changed in the same minute it last saw it, and a folder changed in
    // the same second: dating the change later stands for an edit made some time after the scan.
    private fun File.datedLater() = check(setLastModified(System.currentTimeMillis() + 120_000))

    @Test
    fun `dado a chave só no cabeçalho, quando peço uma página sem apiKey na URL, então o Kavita recusa com 400`() {
        val chapter = work("First Work").chapters().single()

        val answer = KavitaServer.get("/api/Reader/image?chapterId=${chapter.id}&page=0", key = KavitaServer.authKey)

        assertEquals(400, answer.code)
    }

    @Test
    fun `dado uma chave errada, quando peço a versão, então o Kavita responde 401`() {
        assertEquals(401, KavitaServer.get("/api/Plugin/version?apiKey=invented-key-1234").code)
    }

    @Test
    fun `dado uma página servida, quando leio o Cache-Control, então ele não guarda nada`() {
        val chapter = work("First Work").chapters().single()
        val path = "/api/Reader/image?chapterId=${chapter.id}&page=0&apiKey=${KavitaServer.authKey}"

        val (_, cacheControl) = KavitaServer.headers(path)

        assertEquals("private, max-age=0, stale-while-revalidate=0", cacheControl)
    }

    @Test
    fun `dado um capítulo de 12 páginas, quando gravo a página 12, então ele fica lido`() {
        val work = work("First Work")

        KavitaServer.saveProgress(work, work.chapters().single(), page = 12)

        val chapter = work.chapters().single()
        assertEquals(chapter.pages, chapter.pagesRead)
    }

    @Test
    fun `dado a página 2 gravada, quando outro cliente grava a 1 com data antiga, então o Kavita guarda 1`() {
        val work = work("Second Work")
        val chapter = work.chapters().first()

        KavitaServer.saveProgress(work, chapter, page = 2)
        KavitaServer.saveProgress(work, chapter, page = 1, lastModifiedUtc = "2020-01-01T00:00:00Z")

        assertEquals(1, KavitaServer.progress(chapter).getValue("pageNum").jsonPrimitive.int)
    }

    @Test
    fun `dado um capítulo sem progresso, quando gravo a página 0, então nenhum registro é criado`() {
        val work = work("Second Work")
        val chapter = work.chapters().last()

        KavitaServer.saveProgress(work, chapter, page = 0)

        // Without a record the answer is invented by the server, with no volume.
        assertEquals(0, KavitaServer.progress(chapter).getValue("volumeId").jsonPrimitive.int)
    }

    @Test
    fun `dado um capítulo com progresso, quando a biblioteca é varrida de novo sem mudança, então o id e o progresso continuam`() {
        val work = mutableWork("Stable Work")
        val before = work.chapters().single()
        KavitaServer.saveProgress(work, before, page = 2)

        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val after = work.chapters().single()
        assertEquals(before.id, after.id)
        assertEquals(2, after.pagesRead)
    }

    @Test
    fun `dado um capítulo com progresso, quando o arquivo é trocado por outro de mesmo nome, então o id e o progresso continuam com as páginas novas`() {
        val work = mutableWork("Replaced Work")
        val before = work.chapters().single()
        KavitaServer.saveProgress(work, before, page = 2)

        writeChapter(mutableFolder, "Replaced Work", number = 1, pages = 5)
        chapterFile("Replaced Work", 1).run {
            datedLater()
            parentFile.datedLater()
        }
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val after = work.chapters().single()
        assertEquals(before.id, after.id)
        assertEquals(2, after.pagesRead)
        assertEquals(5, after.pages)
    }

    @Test
    fun `dado um capítulo novo na pasta, quando a varredura termina, então os ids antigos não mudam`() {
        val work = mutableWork("Growing Work")
        val before = work.chapters().single()

        writeChapter(mutableFolder, "Growing Work", number = 2, pages = 3)
        chapterFile("Growing Work", 2).parentFile.datedLater()
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val chapters = work.chapters()
        assertEquals(2, chapters.size)
        assertEquals(before.id, chapters.first().id)
    }

    @Test
    fun `dado um capítulo com progresso, quando o arquivo some e volta, então ele volta com outro id e sem progresso`() {
        val work = mutableWork("Vanishing Work")
        val before = work.chapters().last()
        KavitaServer.saveProgress(work, before, page = 2)

        val file = chapterFile("Vanishing Work", 2)
        check(file.delete())
        file.parentFile.datedLater()
        KavitaServer.rescan(KavitaServer.mutableLibraryId)
        assertEquals(1, work.chapters().size)

        writeChapter(mutableFolder, "Vanishing Work", number = 2, pages = 3)
        file.parentFile.datedLater()
        KavitaServer.rescan(KavitaServer.mutableLibraryId)

        val after = work.chapters().last()
        assertNotEquals(before.id, after.id)
        assertEquals(0, after.pagesRead)
    }
}
