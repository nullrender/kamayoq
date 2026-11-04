package app.kamayoq.core.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderNavigationTest {

    @Test
    fun `dado o quadro principal, então nenhuma navegação passa, nem para a própria origem`() {
        for (url in listOf(
            "https://appassets.androidplatform.net/reader/index.html",
            "https://exemplo.invalid/",
            "javascript:alert(1)",
            "intent://x#Intent;end",
            "about:srcdoc",
        )) {
            assertFalse(ReaderNavigation.allows(url, isMainFrame = true), url)
        }
    }

    @Test
    fun `dado um subquadro, então só a seção do livro em srcdoc e o blob da própria origem passam`() {
        assertTrue(ReaderNavigation.allows("about:srcdoc", isMainFrame = false))
        assertTrue(ReaderNavigation.allows("blob:https://appassets.androidplatform.net/0b9c3c1e-1", isMainFrame = false))
    }

    @Test
    fun `dado um subquadro indo para qualquer outro lugar, então a navegação é negada`() {
        for (url in listOf(
            "https://appassets.androidplatform.net/reader/PWNED",
            "blob:https://exemplo.invalid/1",
            "blob:null/1",
            "about:blank",
            "about:srcdoc#x",
            "data:text/html,x",
            "javascript:alert(1)",
            "file:///data/data/app.kamayoq.android/databases/kamayoq.db",
            "",
        )) {
            assertFalse(ReaderNavigation.allows(url, isMainFrame = false), url)
        }
    }
}
