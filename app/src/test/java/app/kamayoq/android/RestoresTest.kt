package app.kamayoq.android

import app.kamayoq.core.backup.Backup
import app.kamayoq.data.RestoreOpening
import app.kamayoq.data.RestoreResult
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestoresTest {

    private val backup = Backup("r550", Instant.parse("2026-11-18T22:00:00Z"), works = emptyList())

    @Test
    fun `dado um arquivo sendo lido, quando escolhem outro, então a segunda leitura não começa`() {
        val restores = Restores(CoroutineScope(Dispatchers.Unconfined))
        val first = CompletableDeferred<RestoreOpening>()
        var started = 0
        val read: suspend () -> RestoreOpening = {
            started++
            first.await()
        }

        restores.open(read)
        restores.open(read)
        first.complete(RestoreOpening.Ready(backup))

        assertEquals(1, started)
        assertFalse(restores.reading)
        assertEquals(backup, restores.offer)
    }

    @Test
    fun `dado um backup lido, quando confirmo, então ele é aplicado e o resultado fica`() {
        val restores = Restores(CoroutineScope(Dispatchers.Unconfined))
        restores.open { RestoreOpening.Ready(backup) }
        var applied: Backup? = null

        restores.confirm {
            applied = it
            RestoreResult.Restored(null)
        }

        assertEquals(backup, applied)
        assertNull(restores.offer)
        assertFalse(restores.restoring)
        assertEquals(RestoreResult.Restored(null), restores.result)
    }

    @Test
    fun `dado um backup lido, quando cancelo, então nada é aplicado`() {
        val restores = Restores(CoroutineScope(Dispatchers.Unconfined))
        restores.open { RestoreOpening.Ready(backup) }
        var applied = false

        restores.cancel()
        restores.confirm {
            applied = true
            RestoreResult.Restored(null)
        }

        assertFalse(applied)
        assertNull(restores.offer)
        assertNull(restores.result)
    }

    @Test
    fun `dado um arquivo recusado, quando a leitura termina, então não há o que confirmar e o resultado diz por quê`() {
        val restores = Restores(CoroutineScope(Dispatchers.Unconfined))

        restores.open { RestoreOpening.Refused(RestoreResult.NotEmpty) }

        assertNull(restores.offer)
        assertEquals(RestoreResult.NotEmpty, restores.result)
    }
}
