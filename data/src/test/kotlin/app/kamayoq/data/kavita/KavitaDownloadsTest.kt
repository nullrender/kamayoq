package app.kamayoq.data.kavita

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.data.StoredChapter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Slice 3 of the v1 interface: the chapters the person downloads from the Work screen (decision 5).
class KavitaDownloadsTest {
    private val root: File = Files.createTempDirectory("kamayoq-downloads").toFile()
    private val cache = File(root, "cache")
    private val downloads = File(root, "downloads")

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    private fun chapter(path: String = "9", needsCheck: Boolean = false) = StoredChapter(
        "c$path", "w", path, "Cap. $path", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, present = true,
        needsContentCheck = needsCheck, origin = "kavita",
    )

    private class Answers(private val write: (File) -> Unit) : KavitaClient() {
        var calls = 0
        override fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) {
            calls++
            write(target)
        }
    }

    private val server = { "http://a" to "key" }

    @Test
    fun `dado um capítulo não baixado, quando baixo, então o arquivo fica na pasta dos downloads e ele conta como baixado`() {
        val files = KavitaChapterFiles(Answers { it.writeText("pages") }, cache, downloads)

        assertTrue(files.download(chapter(), { true }, server))

        assertEquals("pages", File(downloads, "9.cbz").readText())
        assertTrue(files.isDownloaded(chapter()))
    }

    @Test
    fun `dado um capítulo já baixado, quando peço de novo, então nada vem e a resposta diz que já estava`() {
        val client = Answers { it.writeText("pages") }
        val files = KavitaChapterFiles(client, cache, downloads)
        files.download(chapter(), { true }, server)

        assertFalse(files.download(chapter(), { true }, server))

        assertEquals(1, client.calls)
    }

    @Test
    fun `dado o capítulo já aberto no leitor e guardado no cache, quando baixo, então ele passa para os downloads sem rede`() {
        cache.mkdirs()
        File(cache, "9.cbz").writeText("opened")
        val files = KavitaChapterFiles(Answers { error("Must not download") }, cache, downloads)

        assertTrue(files.download(chapter(), { true }, server))

        assertEquals("opened", File(downloads, "9.cbz").readText())
        assertFalse(File(cache, "9.cbz").exists())
    }

    @Test
    fun `dado um capítulo baixado, quando o leitor pede o arquivo, então vem o baixado sem rede`() {
        val files = KavitaChapterFiles(Answers { it.writeText("pages") }, cache, downloads)
        files.download(chapter(), { true }, server)
        val reader = KavitaChapterFiles(Answers { error("Must not download") }, cache, downloads)

        val kept = reader.file(chapter(), stillWanted = { true }) { error("Must not ask") }

        assertEquals("pages", kept.file.readText())
    }

    @Test
    fun `dado um download que falha no meio, então não fica arquivo nem rascunho, e o capítulo não conta como baixado`() {
        val files = KavitaChapterFiles(Answers { it.writeText("half"); throw IOException("Cut") }, cache, downloads)

        assertFailsWith<IOException> { files.download(chapter(), { true }, server) }

        assertFalse(files.isDownloaded(chapter()))
        assertEquals(emptyList(), downloads.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `dado dois capítulos baixados, quando apago um, então ele sai do aparelho, o outro fica e a conta diz quanto liberou`() {
        val files = KavitaChapterFiles(Answers { it.writeText("12345") }, cache, downloads)
        files.download(chapter("9"), { true }, server)
        files.download(chapter("10"), { true }, server)

        val freed = files.delete(chapter("9"))

        assertEquals(5, freed)
        assertFalse(files.isDownloaded(chapter("9")))
        assertTrue(files.isDownloaded(chapter("10")))
        assertEquals(5, files.downloadedBytes(listOf(chapter("9"), chapter("10"))))
    }

    @Test
    fun `dado um capítulo só no cache do leitor, então ele não conta como baixado`() {
        cache.mkdirs()
        File(cache, "9.cbz").writeText("opened")

        assertFalse(KavitaChapterFiles(Answers { }, cache, downloads).isDownloaded(chapter()))
    }
}
