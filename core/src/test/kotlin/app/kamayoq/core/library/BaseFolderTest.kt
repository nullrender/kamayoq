package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaseFolderTest {

    private fun folder(name: String) = FolderEntry("id:$name", name, isFolder = true, size = 0, modified = null)

    private fun file(name: String) = FolderEntry("id:$name", name, isFolder = false, size = 1, modified = null)

    @Test
    fun `dado a pasta-base com Library e Backups, então cada uma é achada pelo nome`() {
        val children = listOf(folder("Backups"), folder("Library"), folder("Downloads"))

        assertEquals("id:Library", BaseFolder.find(children, BaseSubfolder.LIBRARY)?.id)
        assertEquals("id:Backups", BaseFolder.find(children, BaseSubfolder.BACKUPS)?.id)
    }

    @Test
    fun `dado a pasta library criada à mão em minúsculas, então ela é a biblioteca`() {
        assertEquals("id:library", BaseFolder.find(listOf(folder("library")), BaseSubfolder.LIBRARY)?.id)
    }

    @Test
    fun `dado Library e library juntas, então vale a de nome exato`() {
        val children = listOf(folder("library"), folder("Library"))

        assertEquals("id:Library", BaseFolder.find(children, BaseSubfolder.LIBRARY)?.id)
    }

    @Test
    fun `dado um arquivo chamado Library, então ele não é a biblioteca`() {
        assertNull(BaseFolder.find(listOf(file("Library")), BaseSubfolder.LIBRARY))
    }

    @Test
    fun `dado a pasta-base só com as obras soltas, então não há biblioteca`() {
        val children = listOf(folder("Manhwa"), folder("Quadrinhos"), file("solto.cbz"))

        assertNull(BaseFolder.find(children, BaseSubfolder.LIBRARY))
    }
}
