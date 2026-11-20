package app.kamayoq.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshestReadsTest {

    @Test
    fun `dado duas leituras que terminam na ordem em que começaram, então as duas são mostradas`() {
        val reads = FreshestReads()
        val first = reads.begin()
        val second = reads.begin()

        assertTrue(reads.accept(first))
        assertTrue(reads.accept(second))
    }

    // Found in CI on 20/11/2026: Kavita's read of the empty store ended after the folder's reading
    // had shown its works, and the Library said it had none (run 44833020364).
    @Test
    fun `dado uma leitura mais antiga que termina depois de uma mais nova, então a tela fica com a mais nova`() {
        val reads = FreshestReads()
        val older = reads.begin()
        val newer = reads.begin()

        assertTrue(reads.accept(newer))
        assertFalse(reads.accept(older))
    }
}
