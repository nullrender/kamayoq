package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderPolicyTest {

    private val scriptSources = ReaderPolicy.contentSecurityPolicy.split(';')
        .map { it.trim() }
        .single { it.startsWith("script-src ") }

    // Whole text, not pieces: a `*` or a bare `https:` slipped into any directive would pass a search
    // for addresses.
    @Test
    fun `dado a CSP, então ela é exatamente a que foi medida, diretiva por diretiva`() {
        assertEquals(
            listOf(
                "default-src 'none'",
                "script-src https://appassets.androidplatform.net/reader/",
                "style-src 'unsafe-inline' blob:",
                "img-src blob: data:",
                "font-src blob:",
                "media-src blob:",
                "connect-src https://appassets.androidplatform.net/book/",
                "frame-src blob:",
                "base-uri 'none'",
                "form-action 'none'",
                "object-src 'none'",
            ),
            ReaderPolicy.contentSecurityPolicy.split("; "),
        )
    }

    @Test
    fun `dado a CSP, então script-src só aceita a pasta do leitor`() {
        assertEquals("script-src https://appassets.androidplatform.net/reader/", scriptSources)
    }

    @Test
    fun `dado a CSP, então script-src não tem self, unsafe-inline nem unsafe-eval`() {
        for (forbidden in listOf("'self'", "unsafe-inline", "unsafe-eval")) assertFalse(forbidden in scriptSources)
    }

    @Test
    fun `dado a CSP, então tudo que não foi liberado começa negado`() {
        assertContains(ReaderPolicy.contentSecurityPolicy, "default-src 'none'")
        assertContains(ReaderPolicy.contentSecurityPolicy, "base-uri 'none'")
        assertContains(ReaderPolicy.contentSecurityPolicy, "form-action 'none'")
        assertContains(ReaderPolicy.contentSecurityPolicy, "object-src 'none'")
    }

    @Test
    fun `dado a CSP, então nenhuma diretiva aceita a rede fora da própria origem`() {
        val addresses = Regex("""\w+://[^\s;]+""").findAll(ReaderPolicy.contentSecurityPolicy).map { it.value }.toList()
        assertTrue(addresses.isNotEmpty())
        assertTrue(addresses.all { it.startsWith("https://appassets.androidplatform.net/") })
    }
}
