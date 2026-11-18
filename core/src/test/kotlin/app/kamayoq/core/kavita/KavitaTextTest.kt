package app.kamayoq.core.kavita

import app.kamayoq.core.kavita.KavitaProgress.Decision
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.KavitaDevice
import app.kamayoq.core.progress.TextPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KavitaTextTest {
    private val at: Instant = Instant.parse("2026-11-18T14:10:00Z")

    private fun place(section: Int, sections: Int) =
        TextPosition(section, sections, "epubcfi(/6/4!/4/2/1:0)", checkNotNull(BasisPoints.fromFraction(0.5)), at, "phone-a")

    @Test
    fun `dado a seção 2 de 5 com marcador, quando converto, então pageNum é 2 com o marcador`() {
        assertEquals(
            KavitaPlace(2, "//body/p[3]"),
            KavitaText.toRemote(place(2, 5), read = false, marker = "//body/p[3]", pageCount = 5, remoteHasRecord = true),
        )
    }

    @Test
    fun `dado pages diferente do número de seções, quando converto, então nada é enviado`() {
        assertNull(KavitaText.toRemote(place(2, 5), read = false, marker = "//body/p[3]", pageCount = 6, remoteHasRecord = true))
    }

    @Test
    fun `dado a primeira seção e nenhum registro no Kavita, quando converto, então o lugar fica aqui`() {
        assertNull(KavitaText.toRemote(place(0, 5), read = false, marker = "//body/p[3]", pageCount = 5, remoteHasRecord = false))
    }

    @Test
    fun `dado a primeira seção com registro no Kavita, quando converto, então pageNum é 0 com o marcador`() {
        assertEquals(
            KavitaPlace(0, "//body/p[3]"),
            KavitaText.toRemote(place(0, 5), read = false, marker = "//body/p[3]", pageCount = 5, remoteHasRecord = true),
        )
    }

    @Test
    fun `dado o livro lido até o fim aqui, quando converto, então pageNum é o total`() {
        assertEquals(
            KavitaPlace(5, "//body/p[9]"),
            KavitaText.toRemote(place(4, 5), read = true, marker = "//body/p[9]", pageCount = 5, remoteHasRecord = true),
        )
    }

    @Test
    fun `dado o livro lido e uma releitura numa seção antes da última, quando converto, então vai o lugar da releitura`() {
        assertEquals(
            KavitaPlace(1, null),
            KavitaText.toRemote(place(1, 5), read = true, marker = null, pageCount = 5, remoteHasRecord = true),
        )
    }

    @Test
    fun `dado a última seção sem o bloco de fim, quando converto, então vai a seção, não o lido`() {
        assertEquals(
            KavitaPlace(4, null),
            KavitaText.toRemote(place(4, 5), read = false, marker = null, pageCount = 5, remoteHasRecord = true),
        )
    }

    // From the independent review of milestone C (18/11): the marker comes from the reader, already
    // in a known form, or is the one agreed with Kavita, which must stay as Kavita has it, or the next
    // sync takes the server's own marker for news.
    @Test
    fun `dado o marcador acordado com o Kavita numa forma que o leitor não acha, quando converto, então ele vai como está`() {
        assertEquals(
            KavitaPlace(2, "idx:12"),
            KavitaText.toRemote(place(2, 5), read = false, marker = "idx:12", pageCount = 5, remoteHasRecord = true),
        )
    }

    @Test
    fun `dado idx 12 do Kavita, então o lugar abre no início da seção, sem marcador`() {
        val remote = checkNotNull(KavitaText.fromRemote(KavitaPlace(3, "idx:12"), pageCount = 5, at))

        assertEquals(3, remote.position.section)
        assertNull(remote.marker)
    }

    @Test
    fun `dado os marcadores que o leitor do Kavita escreve, então todos são aceitos`() {
        for (marker in listOf("//body", "//body/p[2]", "//body/div[1]/section[12]/h2[1]", """id("x")""", """id("cap-3")/p[4]""")) {
            assertEquals(marker, KavitaText.markerOrNull(marker), marker)
        }
    }

    @Test
    fun `dado marcadores de outros clientes ou malformados, então nenhum é aceito`() {
        val refused = listOf(
            "idx:12", "//div[1]/p[2]", "/body/p[2]", "//body/p[0]", "//body/p", "//body/P[2]", "//body/p[2]/text()",
            """id("a")"b")""", "//body/p[2] | //body/p[3]", "", "//body/p[1]".repeat(200),
        )
        for (marker in refused) assertNull(KavitaText.markerOrNull(marker), marker)
    }

    @Test
    fun `dado a página 2 de 5 no Kavita, então o lugar é a seção 2 de 5, do Kavita, sem marca de lido`() {
        val remote = checkNotNull(KavitaText.fromRemote(KavitaPlace(2, "//body/p[3]"), pageCount = 5, at))

        assertEquals(2 to 5, remote.position.section to remote.position.sections)
        assertEquals(KavitaDevice.NAME, remote.position.device)
        assertEquals("//body/p[3]", remote.marker)
        assertFalse(remote.read)
    }

    @Test
    fun `dado o lido do Kavita num EPUB, então o livro fica lido e reabre no marcador da última seção`() {
        val remote = checkNotNull(KavitaText.fromRemote(KavitaPlace(5, "//body/p[7]"), pageCount = 5, at))

        assertEquals(4, remote.position.section)
        assertEquals("//body/p[7]", remote.marker)
        assertTrue(remote.read)
    }

    @Test
    fun `dado um livro sem seções no Kavita, então não há lugar`() {
        assertNull(KavitaText.fromRemote(KavitaPlace(0, null), pageCount = 0, at))
    }

    @Test
    fun `dado nada mudado, então está em dia`() {
        assertEquals(Decision.IN_SYNC, KavitaText.decide(KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[1]")))
    }

    @Test
    fun `dado só o marcador mudado aqui, então envia`() {
        assertEquals(Decision.SEND, KavitaText.decide(KavitaPlace(2, "//body/p[5]"), KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[1]")))
    }

    @Test
    fun `dado a mesma página no Kavita com um marcador diferente do acordo, então é mudança do Kavita`() {
        assertEquals(Decision.ADOPT, KavitaText.decide(KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[8]")))
    }

    @Test
    fun `dado os dois lados mudados só no marcador da mesma seção, então não pergunta e vale o daqui`() {
        assertEquals(Decision.SEND, KavitaText.decide(KavitaPlace(2, "//body/p[5]"), KavitaPlace(2, "//body/p[1]"), KavitaPlace(2, "//body/p[8]")))
    }

    @Test
    fun `dado os dois lados mudados para seções diferentes, então é conflito`() {
        assertEquals(Decision.CONFLICT, KavitaText.decide(KavitaPlace(3, null), KavitaPlace(2, "//body/p[1]"), KavitaPlace(4, "//body/p[8]")))
    }

    @Test
    fun `dado os dois lados no mesmo lugar, então só registra o acordo`() {
        assertEquals(Decision.AGREE, KavitaText.decide(KavitaPlace(3, "//body/p[2]"), KavitaPlace(2, null), KavitaPlace(3, "//body/p[2]")))
    }

    @Test
    fun `dado nenhum acordo e nada aqui, quando o Kavita tem um lugar, então adota`() {
        assertEquals(Decision.ADOPT, KavitaText.decide(null, null, KavitaPlace(2, "//body/p[2]")))
    }
}
