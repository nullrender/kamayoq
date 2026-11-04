package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderPermissionTest {

    /** Stands in for Android: records each request and refuses the ones in [refused]. */
    private fun android(vararg refused: FolderAccess): Pair<MutableList<FolderAccess>, (FolderAccess) -> Unit> {
        val asked = mutableListOf<FolderAccess>()
        return asked to { access ->
            asked += access
            if (access in refused) throw SecurityException("No persistable $access grant")
        }
    }

    @Test
    fun `dado que o Android aceita leitura e gravação, então guarda as duas e não pede mais nada`() {
        val (asked, take) = android()

        FolderPermission.keep(take)

        assertEquals(listOf(FolderAccess.READ_WRITE), asked)
    }

    @Test
    fun `dado que o Android recusa a gravação, então guarda só a leitura`() {
        val (asked, take) = android(FolderAccess.READ_WRITE)

        FolderPermission.keep(take)

        assertEquals(listOf(FolderAccess.READ_WRITE, FolderAccess.READ), asked)
    }

    @Test
    fun `dado que o Android recusa até a leitura, então o erro chega a quem pediu`() {
        val (_, take) = android(FolderAccess.READ_WRITE, FolderAccess.READ)

        assertFailsWith<SecurityException> { FolderPermission.keep(take) }
    }
}
