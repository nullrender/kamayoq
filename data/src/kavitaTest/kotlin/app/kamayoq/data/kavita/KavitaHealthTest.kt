package app.kamayoq.data.kavita

import kotlin.test.Test
import kotlin.test.assertEquals

class KavitaHealthTest {
    @Test
    fun `dado um Kavita novo no contêiner, quando pergunto pela saúde, então ele responde Ok`() {
        val answer = KavitaServer.get("/api/Health")

        assertEquals(200, answer.code)
        assertEquals("Ok", answer.body)
    }
}
