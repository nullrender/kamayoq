package app.kamayoq.core.kavita

import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaAddressTest {
    @Test
    fun `dado um endereço com barra no fim, quando normalizo, então ele perde a barra`() {
        assertEquals("http://kavita.example:5000", normalizeKavitaAddress("http://kavita.example:5000/"))
    }

    @Test
    fun `dado o endereço OPDS que o Kavita mostra, quando normalizo, então fica só o endereço do servidor`() {
        assertEquals("http://kavita.example:5000", normalizeKavitaAddress("http://kavita.example:5000/api/opds"))
        assertEquals("http://kavita.example:5000", normalizeKavitaAddress("http://kavita.example:5000/api/opds/key-1234"))
    }

    @Test
    fun `dado um Kavita num caminho base, quando normalizo, então o caminho continua`() {
        assertEquals("https://casa.example/kavita", normalizeKavitaAddress("https://casa.example/kavita/"))
        assertEquals("https://casa.example/kavita", normalizeKavitaAddress("https://casa.example/kavita/api/opds"))
    }

    @Test
    fun `dado um caminho base que contém api, quando normalizo, então ele continua`() {
        assertEquals("https://books.example/api/kavita", normalizeKavitaAddress("https://books.example/api/kavita"))
        assertEquals("https://books.example/api", normalizeKavitaAddress("https://books.example/api/"))
    }
}
