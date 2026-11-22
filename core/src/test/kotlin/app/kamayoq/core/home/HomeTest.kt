package app.kamayoq.core.home

import app.kamayoq.core.progress.ReadingState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTest {

    private val now = Instant.parse("2026-11-21T15:00:00Z")
    private fun hoursAgo(hours: Long): Instant = now.minus(Duration.ofHours(hours))

    @Test
    fun `dado um capítulo que chegou depois da última visita à Obra e não foi lido, então é novo`() {
        assertTrue(Home.isNew(arrivedAt = hoursAgo(2), lastVisit = hoursAgo(5), read = false))
    }

    @Test
    fun `dado um capítulo que chegou numa obra nunca aberta, então é novo`() {
        assertTrue(Home.isNew(arrivedAt = hoursAgo(2), lastVisit = null, read = false))
    }

    @Test
    fun `dado um capítulo que chegou antes da última visita à Obra, então deixou de ser novo`() {
        assertFalse(Home.isNew(arrivedAt = hoursAgo(5), lastVisit = hoursAgo(2), read = false))
    }

    @Test
    fun `dado um capítulo novo já lido, então não é novo`() {
        assertFalse(Home.isNew(arrivedAt = hoursAgo(2), lastVisit = hoursAgo(5), read = true))
    }

    @Test
    fun `dado um capítulo do marco zero, sem hora de chegada, então nunca é novo`() {
        assertFalse(Home.isNew(arrivedAt = null, lastVisit = null, read = false))
    }

    private fun work(id: String, state: ReadingState?, lastVisit: Instant?, vararg chapters: ArrivedChapter) =
        ArrivalWork(id, state, lastVisit, chapters.toList())

    @Test
    fun `dado capítulos novos em obras de cada estado, então Chegou mostra só as obras em Lendo e em Quero ler`() {
        val chapter = { id: String -> ArrivedChapter(id, hoursAgo(1), read = false) }
        val groups = Home.arrived(
            listOf(
                work("reading", ReadingState.READING, null, chapter("r1")),
                work("want", ReadingState.WANT_TO_READ, null, chapter("w1")),
                work("on-hold", ReadingState.ON_HOLD, null, chapter("h1")),
                work("dropped", ReadingState.DROPPED, null, chapter("d1")),
                work("completed", ReadingState.COMPLETED, null, chapter("c1")),
                work("none", null, null, chapter("n1")),
            ),
        )

        assertEquals(setOf("reading", "want"), groups.map { it.workId }.toSet())
    }

    @Test
    fun `dado duas obras com novidades, então a da chegada mais recente vem primeiro, com os capítulos do mais novo ao mais velho`() {
        val groups = Home.arrived(
            listOf(
                work("older", ReadingState.READING, null, ArrivedChapter("o1", hoursAgo(9), false)),
                work(
                    "newer", ReadingState.READING, null,
                    ArrivedChapter("n930", hoursAgo(26), false),
                    ArrivedChapter("n931", hoursAgo(2), false),
                ),
            ),
        )

        assertEquals(listOf("newer", "older"), groups.map { it.workId })
        assertEquals(listOf("n931", "n930"), groups.first().shown.map { it.id })
        assertEquals(hoursAgo(2), groups.first().latest)
        assertEquals(2, groups.first().count)
    }

    @Test
    fun `dado doze capítulos novos numa obra, então o grupo conta doze e mostra os dez mais novos`() {
        val chapters = (1..12).map { ArrivedChapter("c$it", hoursAgo(13L - it), false) }.toTypedArray()

        val group = Home.arrived(listOf(work("w", ReadingState.READING, null, *chapters))).single()

        assertEquals(12, group.count)
        assertEquals((12 downTo 3).map { "c$it" }, group.shown.map { it.id })
    }

    @Test
    fun `dado quinze capítulos que chegaram na mesma leitura, então Chegou mostra os dez últimos na ordem da obra, do mais novo ao mais velho`() {
        // In reading order, as the store lists them: the tie on the arrival must not keep that order.
        val chapters = (1..15).map { ArrivedChapter("c$it", hoursAgo(2), false, order = it) }.toTypedArray()

        val group = Home.arrived(listOf(work("w", ReadingState.READING, null, *chapters))).single()

        assertEquals(15, group.count)
        assertEquals((15 downTo 6).map { "c$it" }, group.shown.map { it.id })
    }

    @Test
    fun `dado uma obra cujos capítulos chegados foram lidos ou vistos na Obra, então ela não entra em Chegou`() {
        val groups = Home.arrived(
            listOf(
                work("read", ReadingState.READING, null, ArrivedChapter("a", hoursAgo(2), read = true)),
                work("visited", ReadingState.READING, hoursAgo(1), ArrivedChapter("b", hoursAgo(2), read = false)),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `dado obras em Lendo lidas em horas diferentes, então Continuar lendo vem da mais recente para a mais antiga`() {
        val result = Home.continueReading(
            listOf(
                ReadingWork("dom", ReadingState.READING, hoursAgo(30)),
                ReadingWork("shadow", ReadingState.READING, hoursAgo(1)),
                ReadingWork("kaoru", ReadingState.READING, hoursAgo(5)),
            ),
        )

        assertEquals(listOf("shadow", "kaoru", "dom"), result)
    }

    @Test
    fun `dado obras fora de Lendo ou nunca abertas, então não entram em Continuar lendo`() {
        val result = Home.continueReading(
            listOf(
                ReadingWork("want", ReadingState.WANT_TO_READ, hoursAgo(1)),
                ReadingWork("on-hold", ReadingState.ON_HOLD, hoursAgo(1)),
                ReadingWork("completed", ReadingState.COMPLETED, hoursAgo(1)),
                ReadingWork("none", null, hoursAgo(1)),
                ReadingWork("never", ReadingState.READING, null),
                ReadingWork("reading", ReadingState.READING, hoursAgo(3)),
            ),
        )

        assertEquals(listOf("reading"), result)
    }

    @Test
    fun `dado uma obra em Lendo só por marcas à mão, sem nada aberto, então ela entra em Continuar lendo na hora da última marca`() {
        val result = Home.continueReading(
            listOf(
                ReadingWork("read", ReadingState.READING, hoursAgo(5)),
                ReadingWork("marked", ReadingState.READING, lastReadAt = null, lastMarkedAt = hoursAgo(2)),
                ReadingWork("nothing", ReadingState.READING, lastReadAt = null, lastMarkedAt = null),
            ),
        )

        assertEquals(listOf("marked", "read"), result)
    }

    @Test
    fun `dado oito obras em Lendo, então Continuar lendo mostra seis, o cartão e a faixa com a próxima na borda`() {
        val result = Home.continueReading((1..8).map { ReadingWork("w$it", ReadingState.READING, hoursAgo(it.toLong())) })

        assertEquals((1..6).map { "w$it" }, result)
    }

    @Test
    fun `dado obras adicionadas depois do marco zero, então Adicionadas vem da mais nova e deixa de fora as do marco zero e as já abertas`() {
        val result = Home.added(
            listOf(
                AddedWork("old", addedAt = null, lastVisit = null),
                AddedWork("tower", addedAt = hoursAgo(20), lastVisit = null),
                AddedWork("opened", addedAt = hoursAgo(10), lastVisit = hoursAgo(9)),
                AddedWork("gachiakuta", addedAt = hoursAgo(3), lastVisit = null),
            ),
        )

        assertEquals(listOf("gachiakuta", "tower"), result)
    }

    @Test
    fun `dado doze obras adicionadas e nunca abertas, então Adicionadas mostra as dez mais novas`() {
        val result = Home.added((1..12).map { AddedWork("w$it", hoursAgo(it.toLong()), null) })

        assertEquals((1..10).map { "w$it" }, result)
    }

    @Test
    fun `dado horas diferentes desde algo, então o tempo se diz em minutos, horas, ontem ou dias`() {
        assertEquals(Elapsed.JustNow, Home.elapsed(now.minusSeconds(20), now))
        assertEquals(Elapsed.Minutes(5), Home.elapsed(now.minus(Duration.ofMinutes(5)), now))
        assertEquals(Elapsed.Hours(2), Home.elapsed(hoursAgo(2), now))
        assertEquals(Elapsed.Hours(23), Home.elapsed(hoursAgo(23), now))
        assertEquals(Elapsed.Yesterday, Home.elapsed(hoursAgo(30), now))
        assertEquals(Elapsed.Days(3), Home.elapsed(hoursAgo(80), now))
    }

    @Test
    fun `dado uma hora no futuro, porque o relógio do aparelho voltou, então diz agora`() {
        assertEquals(Elapsed.JustNow, Home.elapsed(now.plusSeconds(600), now))
    }

    @Test
    fun `dado uma obra lida pela última vez há 13 dias, então diz há quanto tempo, e há 14 dias diz onde a pessoa deixou`() {
        assertFalse(Home.leftLongAgo(now.minus(Duration.ofDays(13)), now))
        assertTrue(Home.leftLongAgo(now.minus(Duration.ofDays(14)), now))
    }
}
