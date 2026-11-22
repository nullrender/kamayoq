package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: Download on one chapter of Arrived says it of that one chapter.
@RunWith(RobolectricTestRunner::class)
class HomeNoticesTest {

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado um capítulo de Chegou que já estava baixando, quando toco Baixar, então o aviso fala dele no singular`() {
        assertEquals(app.getString(R.string.download_one_coming), homeDownloadNotice(app.resources, count = 0, here = false))
    }

    @Test
    fun `dado um capítulo de Chegou que terminou de baixar antes do toque, quando toco Baixar, então o aviso diz que ele já está no aparelho`() {
        assertEquals(app.getString(R.string.download_one_here), homeDownloadNotice(app.resources, count = 0, here = true))
    }

    @Test
    fun `dado um capítulo de Chegou que não estava no aparelho, quando toco Baixar, então o aviso diz que ele vai ser baixado`() {
        assertEquals(app.resources.getQuantityString(R.plurals.download_started, 1, 1), homeDownloadNotice(app.resources, count = 1, here = false))
    }
}
