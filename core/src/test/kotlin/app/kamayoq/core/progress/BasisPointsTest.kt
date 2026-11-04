package app.kamayoq.core.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BasisPointsTest {

    @Test
    fun `dado a fração 0, quando convertida, então é o começo (0)`() {
        assertEquals(BasisPoints.START, BasisPoints.fromFraction(0.0)?.value)
    }

    @Test
    fun `dado a fração 1, quando convertida, então é o fim (10 000)`() {
        assertEquals(BasisPoints.END, BasisPoints.fromFraction(1.0)?.value)
    }

    @Test
    fun `dado Dom Casmurro em 19,37 por cento, quando convertido, então é 1 937`() {
        assertEquals(1_937, BasisPoints.fromFraction(0.1937)?.value)
    }

    @Test
    fun `dado uma fração entre dois inteiros, quando convertida, então arredonda ao mais próximo`() {
        assertEquals(1_235, BasisPoints.fromFraction(0.123456)?.value)
        assertEquals(1_234, BasisPoints.fromFraction(0.123449)?.value)
    }

    @Test
    fun `dado uma fração exatamente no meio entre dois inteiros, quando convertida, então arredonda para cima`() {
        // 0.03125 × 10 000 = 312.5, exact in binary floating point.
        assertEquals(313, BasisPoints.fromFraction(0.03125)?.value)
    }

    @Test
    fun `dado uma fração logo abaixo do fim, quando convertida, então não passa de 10 000`() {
        assertEquals(BasisPoints.END, BasisPoints.fromFraction(0.99996)?.value)
        assertEquals(9_999, BasisPoints.fromFraction(0.99994)?.value)
    }

    @Test
    fun `dado uma fração logo acima do começo, quando convertida, então arredonda para 0 ou 1`() {
        assertEquals(BasisPoints.START, BasisPoints.fromFraction(0.00004)?.value)
        assertEquals(1, BasisPoints.fromFraction(0.00006)?.value)
    }

    @Test
    fun `dado 1,3 e 2,3 por cento, quando convertidos, então a diferença é exatamente 100`() {
        val before = BasisPoints.fromFraction(0.013)!!.value
        val after = BasisPoints.fromFraction(0.023)!!.value
        assertEquals(100, after - before)
    }

    @Test
    fun `dado uma fração ausente, quando convertida, então é recusada e não vira zero`() {
        assertNull(BasisPoints.fromFraction(null))
    }

    @Test
    fun `dado uma fração inválida, quando convertida, então é recusada`() {
        assertNull(BasisPoints.fromFraction(Double.NaN))
        assertNull(BasisPoints.fromFraction(Double.POSITIVE_INFINITY))
        assertNull(BasisPoints.fromFraction(Double.NEGATIVE_INFINITY))
        assertNull(BasisPoints.fromFraction(-0.01))
        assertNull(BasisPoints.fromFraction(1.01))
    }

    @Test
    fun `dado uma fração um fio fora dos limites, quando convertida, então é recusada`() {
        assertNull(BasisPoints.fromFraction(-1e-9))
        assertNull(BasisPoints.fromFraction(1.0 + 1e-9))
    }
}
