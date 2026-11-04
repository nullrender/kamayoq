package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderEngineTest {

    @Test
    fun `dado o WebView 116, então o leitor recusa, e dado 117, então abre`() {
        assertFalse(ReaderEngine.supports("116.0.5845.172"))
        assertTrue(ReaderEngine.supports("117.0.5938.60"))
        assertTrue(ReaderEngine.supports("133.0.6943.137"))
    }

    @Test
    fun `dado uma versão ausente ou ilegível, então o leitor recusa em vez de arriscar`() {
        for (version in listOf(null, "", "abc", ".117", "-118.0")) assertFalse(ReaderEngine.supports(version), "$version")
    }

    @Test
    fun `dado a primeira queda do renderizador, então reabre, e dado a segunda, então para e explica`() {
        val crashes = RendererCrashes()

        assertEquals(RendererCrashes.Decision.REOPEN, crashes.afterCrash())
        assertEquals(RendererCrashes.Decision.STOP, crashes.afterCrash())
        assertEquals(RendererCrashes.Decision.STOP, crashes.afterCrash())
    }
}
