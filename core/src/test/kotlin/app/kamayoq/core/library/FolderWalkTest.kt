package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderWalkTest {

    /** A folder listing over a fixed tree, counting how many times each folder is listed. */
    private class Tree(private val folders: Map<String, List<FolderEntry>>) {
        val listed = mutableListOf<String>()

        fun children(folderId: String): List<FolderEntry> {
            listed += folderId
            return folders.getValue(folderId)
        }
    }

    private fun folder(id: String, name: String) = FolderEntry(id, name, isFolder = true, size = 0, modified = 1)

    private fun file(id: String, name: String, size: Long = 100, modified: Long? = 7) =
        FolderEntry(id, name, isFolder = false, size = size, modified = modified)

    @Test
    fun `dado uma pasta com obras e capítulos, então devolve cada arquivo com o caminho, o identificador e a impressão`() {
        val tree = Tree(
            mapOf(
                "root" to listOf(folder("d1", "Manhwa"), file("f0", "Avulso.cbz", size = 50, modified = null)),
                "d1" to listOf(folder("d2", "Tower of God")),
                "d2" to listOf(file("f1", "Cap. 001.cbz", size = 900, modified = 3), folder("d3", "Cap. 002")),
                "d3" to listOf(file("f2", "001.jpg"), file("f3", "002.jpg")),
            ),
        )

        val files = FolderWalk.walk("root", tree::children)

        assertEquals(
            setOf(
                FoundFile("Avulso.cbz", "f0", FileStamp(50, null)),
                FoundFile("Manhwa/Tower of God/Cap. 001.cbz", "f1", FileStamp(900, 3)),
                FoundFile("Manhwa/Tower of God/Cap. 002/001.jpg", "f2", FileStamp(100, 7)),
                FoundFile("Manhwa/Tower of God/Cap. 002/002.jpg", "f3", FileStamp(100, 7)),
            ),
            files.toSet(),
        )
        assertEquals(4, files.size)
    }

    @Test
    fun `dado várias pastas, então cada uma é listada uma vez só`() {
        val tree = Tree(
            mapOf(
                "root" to listOf(folder("d1", "A"), folder("d2", "B")),
                "d1" to listOf(file("f1", "1.cbz"), file("f2", "2.cbz")),
                "d2" to listOf(folder("d3", "C")),
                "d3" to emptyList(),
            ),
        )

        FolderWalk.walk("root", tree::children)

        assertEquals(listOf("d1", "d2", "d3", "root"), tree.listed.sorted())
    }

    @Test
    fun `dado uma pasta oculta, então ela não é listada e nada dela volta`() {
        val tree = Tree(
            mapOf(
                "root" to listOf(folder("hidden", ".thumbnails"), file("f1", ".nomedia"), folder("d1", "X")),
                "d1" to listOf(file("f2", "Cap. 1.cbz")),
            ),
        )

        val files = FolderWalk.walk("root", tree::children)

        assertEquals(listOf(".nomedia", "X/Cap. 1.cbz"), files.map { it.path }.sorted())
        assertEquals(listOf("d1", "root"), tree.listed.sorted())
    }

    @Test
    fun `dado a pasta escolhida vazia, então nada volta`() {
        assertEquals(emptyList(), FolderWalk.walk("root", Tree(mapOf("root" to emptyList()))::children))
    }

    @Test
    fun `dado arquivos de mesmo nome em pastas diferentes, então os caminhos são diferentes`() {
        val tree = Tree(
            mapOf(
                "root" to listOf(folder("d1", "A"), folder("d2", "B")),
                "d1" to listOf(file("f1", "Cap. 1.cbz")),
                "d2" to listOf(file("f2", "Cap. 1.cbz")),
            ),
        )

        val files = FolderWalk.walk("root", tree::children)

        assertEquals(mapOf("A/Cap. 1.cbz" to "f1", "B/Cap. 1.cbz" to "f2"), files.associate { it.path to it.id })
    }

    private val library = Tree(
        mapOf(
            "root" to listOf(folder("d1", "Manhwa"), folder("d9", "Quadrinhos")),
            "d1" to listOf(folder("d2", "Tower of God")),
            "d2" to listOf(file("f1", "Cap. 001.cbz"), folder("d3", "Cap. 002")),
            "d3" to listOf(file("f2", "001.jpg")),
            "d9" to emptyList(),
        ),
    )

    @Test
    fun `dado o caminho de um capítulo em arquivo, então acha o arquivo listando só as pastas do caminho`() {
        val found = FolderWalk.find("root", "Manhwa/Tower of God/Cap. 001.cbz", library::children)

        assertEquals("f1", found?.id)
        assertEquals(listOf("root", "d1", "d2"), library.listed)
    }

    @Test
    fun `dado o caminho de um capítulo em pasta de imagens, então acha a pasta`() {
        val found = FolderWalk.find("root", "Manhwa/Tower of God/Cap. 002", library::children)

        assertEquals(folder("d3", "Cap. 002"), found)
    }

    @Test
    fun `dado um caminho que não existe mais, então não acha nada, sem erro`() {
        assertNull(FolderWalk.find("root", "Manhwa/Tower of God/Cap. 003.cbz", library::children))
        assertNull(FolderWalk.find("root", "Mangá/Tower of God/Cap. 001.cbz", library::children))
    }

    @Test
    fun `dado um caminho que passa por dentro de um arquivo, então não acha nada`() {
        assertNull(FolderWalk.find("root", "Manhwa/Tower of God/Cap. 001.cbz/001.jpg", library::children))
    }
}
