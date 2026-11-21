package app.kamayoq.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkCoversTest {
    private val folder: File = Files.createTempDirectory("kamayoq-covers").toFile()

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun `dado rascunhos de capa de um processo que morreu, quando a pasta das capas abre, então eles somem e as capas ficam`() {
        File(folder, "work-1.part").writeText("half")
        File(folder, "work-2").writeText("cover")

        WorkCovers(folder)

        assertEquals(listOf("work-2"), folder.list().orEmpty().toList())
    }

    @Test
    fun `dado uma capa que não pode tomar o lugar da antiga, quando guardo, então não fica rascunho na pasta`() {
        // A folder where the cover goes: the rename fails.
        File(folder, "work-1").mkdirs()

        runCatching { WorkCovers(folder).keep("work-1", byteArrayOf(1, 2, 3)) }

        assertEquals(listOf("work-1"), folder.list().orEmpty().toList())
    }
}
