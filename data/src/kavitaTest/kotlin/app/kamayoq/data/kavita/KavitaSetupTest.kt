package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaSetupTest {
    @Test
    fun `dado um Kavita sem administrador, quando o preparo termina, então a chave criada abre a lista de bibliotecas, vazia`() {
        KavitaServer.authKey

        assertEquals(200, KavitaServer.librariesAfterSetup.code)
        assertEquals("[]", KavitaServer.librariesAfterSetup.body)
    }

    @Test
    fun `dado o preparo feito, quando pergunto se existe administrador, então a resposta é sim`() {
        KavitaServer.authKey

        assertEquals("true", KavitaServer.get("/api/Admin/exists").body)
    }

    @Test
    fun `dado uma chave inventada, quando peço as bibliotecas, então o Kavita responde 401`() {
        val answer = KavitaServer.get("/api/Library/libraries", key = "invented-key-1234")

        assertEquals(401, answer.code)
    }
}
