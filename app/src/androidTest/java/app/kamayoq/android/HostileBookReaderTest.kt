package app.kamayoq.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Valid only while HostileBookControlTest shows every trace with the defenses off.
@RunWith(AndroidJUnit4::class)
class HostileBookReaderTest {
    @Test
    fun `dado o leitor com a CSP quando percorro o livro hostil então nada de fora é pedido e nenhum script do livro roda`() {
        val traces = HostileBookRun(::ReaderClient).traces()

        // The app's own reader may still run inside the book: the CSP allows its folder. Taking
        // allow-scripts out of the fork's frame is what stops that one (piece 10).
        assertEquals(emptySet<String>(), traces - "reader-script")
    }
}
