package app.kamayoq.android

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedOpeningTest {
    private val finished = mutableListOf<String>()
    private val owned = Owned<String> { finished += it }

    @Test
    fun `dado uma abertura que terminou e a tela que saiu antes de mostrá-la, quando fecha, então ela termina`() {
        owned.take(1, "abertura 1")

        owned.close()

        assertEquals(listOf("abertura 1"), finished)
    }

    @Test
    fun `dado uma abertura nova, quando chega, então a anterior termina e só a nova fica`() {
        owned.take(1, "abertura 1")
        owned.take(2, "abertura 2")

        owned.close()

        assertEquals(listOf("abertura 1", "abertura 2"), finished)
    }

    @Test
    fun `dado nada aberto, quando fecha, então nada termina`() {
        owned.close()

        assertEquals(emptyList<String>(), finished)
    }

    @Test
    fun `dado a tela já fechada, quando uma abertura chega atrasada, então ela termina na hora`() {
        owned.close()

        owned.take(1, "atrasada")

        assertEquals(listOf("atrasada"), finished)
    }

    @Test
    fun `dado uma abertura antiga que chega depois da nova, então ela termina na hora e a nova continua`() {
        owned.take(2, "nova")

        owned.take(1, "antiga")

        assertEquals(listOf("antiga"), finished)
        owned.close()
        assertEquals(listOf("antiga", "nova"), finished)
    }
}
