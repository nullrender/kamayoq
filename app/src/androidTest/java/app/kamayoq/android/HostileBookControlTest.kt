package app.kamayoq.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kamayoq.epubfixtures.EpubFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Enters green on purpose: it measures the engine, not the reader, and a red here would be staged.
@RunWith(AndroidJUnit4::class)
class HostileBookControlTest {
    @Test
    fun `dado a página de controle sem defesas quando percorro o livro hostil então cada sentinela dispara`() {
        val traces = HostileBookRun(::ControlClient).traces()

        assertEquals((EpubFixtures.HOSTILE_SCRIPTS + EpubFixtures.HOSTILE_REQUESTS).toSet(), traces)
    }
}
