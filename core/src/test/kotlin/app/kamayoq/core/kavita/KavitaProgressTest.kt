package app.kamayoq.core.kavita

import app.kamayoq.core.progress.StripPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KavitaProgressTest {
    private val at: Instant = Instant.parse("2026-10-25T14:10:00Z")

    private fun position(page: Int, total: Int) = StripPosition(page, total, at, "phone-a")

    @Test
    fun `dado a página 0 de 5, quando converto, então não há o que enviar`() {
        assertNull(KavitaProgress.toRemote(position(0, 5), pageCount = 5))
    }

    @Test
    fun `dado a página 1 de 5, quando converto, então pageNum é 1`() {
        assertEquals(1, KavitaProgress.toRemote(position(1, 5), pageCount = 5))
    }

    @Test
    fun `dado o bloco de fim de um capítulo de 5, quando converto, então pageNum é 5`() {
        assertEquals(5, KavitaProgress.toRemote(position(5, 5), pageCount = 5))
    }

    @Test
    fun `dado nunca aberto, quando converto, então não há o que enviar`() {
        assertNull(KavitaProgress.toRemote(null, pageCount = 5))
    }

    @Test
    fun `dado o Kavita contando menos páginas, quando converto, então a página fica antes do fim e o bloco de fim vai ao total dele`() {
        assertEquals(3, KavitaProgress.toRemote(position(5, 6), pageCount = 4))
        assertEquals(4, KavitaProgress.toRemote(position(6, 6), pageCount = 4))
    }

    @Test
    fun `dado pageNum 5 de 5 vindo do Kavita, quando converto de volta, então é o bloco de fim, lido`() {
        val back = KavitaProgress.fromRemote(5, pageCount = 5, at)

        assertEquals(position(5, 5).copy(device = "kavita"), back)
        assertTrue(back.isAtEnd)
    }

    @Test
    fun `dado pageNum além do total, quando converto de volta, então fica no fim`() {
        assertEquals(5, KavitaProgress.fromRemote(9, pageCount = 5, at).page)
    }

    @Test
    fun `dado local e remoto iguais ao acordo, quando decido, então nada acontece`() {
        assertEquals(KavitaProgress.Decision.IN_SYNC, KavitaProgress.decide(local = 7, agreed = 7, remote = 7))
    }

    @Test
    fun `dado só o local mudou, quando decido, então envio`() {
        assertEquals(KavitaProgress.Decision.SEND, KavitaProgress.decide(local = 9, agreed = 7, remote = 7))
    }

    @Test
    fun `dado só o remoto mudou, quando decido, então adoto`() {
        assertEquals(KavitaProgress.Decision.ADOPT, KavitaProgress.decide(local = 7, agreed = 7, remote = 10))
    }

    @Test
    fun `dado os dois mudaram para páginas diferentes, quando decido, então é conflito`() {
        assertEquals(KavitaProgress.Decision.CONFLICT, KavitaProgress.decide(local = 9, agreed = 7, remote = 10))
    }

    @Test
    fun `dado os dois mudaram para a mesma página, quando decido, então só registro o acordo`() {
        assertEquals(KavitaProgress.Decision.AGREE, KavitaProgress.decide(local = 10, agreed = 7, remote = 10))
    }

    @Test
    fun `dado primeira vez, nunca aberto aqui e progresso no Kavita, quando decido, então adoto`() {
        assertEquals(KavitaProgress.Decision.ADOPT, KavitaProgress.decide(local = 0, agreed = null, remote = 10))
    }

    @Test
    fun `dado primeira vez, lido aqui e nada no Kavita, quando decido, então envio`() {
        assertEquals(KavitaProgress.Decision.SEND, KavitaProgress.decide(local = 4, agreed = null, remote = 0))
    }

    @Test
    fun `dado primeira vez com posições diferentes nos dois lados, quando decido, então é conflito`() {
        assertEquals(KavitaProgress.Decision.CONFLICT, KavitaProgress.decide(local = 4, agreed = null, remote = 10))
    }

    @Test
    fun `dado primeira vez e nada nos dois lados, quando decido, então nada acontece`() {
        assertEquals(KavitaProgress.Decision.IN_SYNC, KavitaProgress.decide(local = 0, agreed = null, remote = 0))
    }

    @Test
    fun `dado nada a enviar aqui e o acordo igual ao remoto, quando decido, então nada acontece`() {
        assertEquals(KavitaProgress.Decision.IN_SYNC, KavitaProgress.decide(local = null, agreed = 18, remote = 18))
    }

    @Test
    fun `dado nada a enviar aqui e o remoto mudado, quando decido, então adoto`() {
        assertEquals(KavitaProgress.Decision.ADOPT, KavitaProgress.decide(local = null, agreed = 18, remote = 20))
    }

    @Test
    fun `dado o fim do capítulo, então conta como lido e a página antes dele não`() {
        assertTrue(KavitaProgress.isRead(5, pageCount = 5))
        assertFalse(KavitaProgress.isRead(4, pageCount = 5))
    }

    @Test
    fun `dado um capítulo sem páginas no Kavita, então não há o que sincronizar`() {
        assertNull(KavitaProgress.fromRemoteOrNull(0, pageCount = 0, at))
    }
}
