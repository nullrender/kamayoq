package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderLinkTest {

    @Test
    fun `dado um link http ou https, então ele pede confirmação mostrando a URL real`() {
        assertEquals(ReaderLink.AskFirst("https://exemplo.invalid/a?b=1"), ReaderLink.classify("https://exemplo.invalid/a?b=1"))
        assertEquals(ReaderLink.AskFirst("http://exemplo.invalid/"), ReaderLink.classify("HTTP://exemplo.invalid/"))
    }

    @Test
    fun `dado um link javascript, intent, file ou content, então ele não abre`() {
        val refused = listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "intent://exemplo#Intent;scheme=https;end",
            "file:///data/data/app.kamayoq.android/databases/kamayoq.db",
            "content://app.kamayoq.android/x",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://appassets.androidplatform.net/1",
            "market://details?id=x",
            "tel:+5500000000000",
            "",
        )
        for (href in refused) assertEquals(ReaderLink.Refused, ReaderLink.classify(href), href)
    }

    @Test
    fun `dado um link disfarçado com espaço ou caractere de controle, então ele não abre`() {
        val disguised = listOf(
            " javascript:alert(1)",
            "java\tscript:alert(1)",
            "https://exemplo.invalid/\njavascript:alert(1)",
            "\u0001https://exemplo.invalid/",
            "https://exemplo.invalid/a b",
        )
        for (href in disguised) assertEquals(ReaderLink.Refused, ReaderLink.classify(href), href)
    }

    // The confirmation shows this text as the real address, so nothing in it may change how it reads.
    @Test
    fun `dado um link com caractere invisível, de direção ou fora do ASCII, então ele não abre`() {
        val disguised = listOf(
            "https://exemplo.invalid/‮fdp.exe",
            "https://exemplo​.invalid/",
            "https://exemplo.invalid/ a",
            "https://exemplo.invalid/ a",
            "https://exemplo.invalid/\u0085a",
            "https://exemplo.invalid/ação",
        )
        for (href in disguised) assertEquals(ReaderLink.Refused, ReaderLink.classify(href), href)
    }

    @Test
    fun `dado um link com usuário antes do arroba, então ele não abre`() {
        assertEquals(ReaderLink.Refused, ReaderLink.classify("https://banco.exemplo@outro.exemplo/"))
        assertEquals(ReaderLink.Refused, ReaderLink.classify("https://a:b@outro.exemplo/"))
    }

    @Test
    fun `dado um arroba depois do endereço, então o link continua pedindo confirmação`() {
        assertEquals(ReaderLink.AskFirst("https://exemplo.invalid/perfil/@nome"), ReaderLink.classify("https://exemplo.invalid/perfil/@nome"))
    }

    @Test
    fun `dado https sem endereço, então ele não abre`() {
        assertEquals(ReaderLink.Refused, ReaderLink.classify("https://"))
        assertEquals(ReaderLink.Refused, ReaderLink.classify("https:exemplo.invalid"))
    }
}
