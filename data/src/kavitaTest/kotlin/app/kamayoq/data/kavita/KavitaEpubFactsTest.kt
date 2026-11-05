package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * What Kavita 0.9.1 does with an EPUB's page and marker, which the text reader's sync stands on.
 * As in [KavitaFactsTest], no honest red commit: docs/decisions/kavita-step-3-2026-10-30.md,
 * decision 4.
 */
class KavitaEpubFactsTest {
    private fun book(name: String) = KavitaServer.works(KavitaServer.bookLibraryId).single { it.name == name }

    private fun KavitaWork.chapter() = KavitaServer.chapters(id).single()

    private fun marker(chapter: KavitaChapter) = KavitaServer.progress(chapter).getValue("bookScrollId")

    @Test
    fun `dado um EPUB de três seções, uma fora da leitura linear, então pages é 3`() {
        assertEquals(3, book("Spine Book").chapter().pages)
    }

    @Test
    fun `dado a página 0 com marcador e nenhum registro, quando gravo, então o Kavita responde 200 e não guarda nada`() {
        val work = book("Unmarked Book")
        val chapter = work.chapter()

        KavitaServer.saveProgress(work, chapter, page = 0, bookScrollId = "//body/p[2]")

        // Without a record the answer is invented by the server, with no volume and no marker.
        assertEquals(0, KavitaServer.progress(chapter).getValue("volumeId").jsonPrimitive.int)
        assertEquals(JsonNull, marker(chapter))
    }

    @Test
    fun `dado um registro já existente, quando gravo a página 0 com marcador, então o marcador fica guardado`() {
        val work = book("Zero Book")
        val chapter = work.chapter()
        KavitaServer.saveProgress(work, chapter, page = 1, bookScrollId = "//body/p[1]")

        KavitaServer.saveProgress(work, chapter, page = 0, bookScrollId = "//body/p[2]")

        val progress = KavitaServer.progress(chapter)
        assertEquals(0, progress.getValue("pageNum").jsonPrimitive.int)
        assertEquals("//body/p[2]", progress.getValue("bookScrollId").jsonPrimitive.content)
    }

    @Test
    fun `dado a página 1 com bookScrollId em XPath, quando leio o progresso, então ele volta igual, byte a byte`() {
        val work = book("Marked Book")
        val chapter = work.chapter()

        KavitaServer.saveProgress(work, chapter, page = 1, bookScrollId = "//body/p[2]")

        assertEquals("//body/p[2]", marker(chapter).jsonPrimitive.content)
    }

    @Test
    fun `dado o marcador na forma id de elemento, quando leio o progresso, então ele volta igual`() {
        val work = book("Id Book")
        val chapter = work.chapter()

        KavitaServer.saveProgress(work, chapter, page = 1, bookScrollId = """id("x")""")

        assertEquals("""id("x")""", marker(chapter).jsonPrimitive.content)
    }

    @Test
    fun `dado o sumário de Book chapters, então page é o índice no spine, contando a seção não linear, e a âncora vai em part`() {
        val contents = KavitaServer.bookContents(book("Spine Book").chapter())

        assertEquals(
            listOf(Triple("One", 0, ""), Triple("Two", 1, ""), Triple("Three", 2, ""), Triple("Three, part", 2, "x")),
            contents.map {
                Triple(
                    it.getValue("title").jsonPrimitive.content,
                    it.getValue("page").jsonPrimitive.int,
                    it.getValue("part").jsonPrimitive.content,
                )
            },
        )
    }

    @Test
    fun `dado dois EPUBs de títulos diferentes na mesma pasta, então são duas séries, com o título do OPF`() {
        val works = KavitaServer.works(KavitaServer.bookLibraryId)
            .filter { work -> KavitaServer.chapters(work.id).any { "/Chapters Folder/" in it.files.single().path } }

        assertEquals(listOf("Alpha Story", "Beta Story"), works.map { it.name }.sorted())
    }
}
