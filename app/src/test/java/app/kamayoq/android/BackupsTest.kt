package app.kamayoq.android

import app.kamayoq.data.BackupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupsTest {

    @Test
    fun `dado um backup em andamento, quando pedem outro, então o segundo não começa`() {
        val backups = Backups(CoroutineScope(Dispatchers.Unconfined))
        val first = CompletableDeferred<Pair<String?, BackupResult>>()
        var started = 0
        val write: suspend () -> Pair<String?, BackupResult> = {
            started++
            first.await()
        }

        backups.start(write)
        backups.start(write)
        first.complete("Kamayoq" to BackupResult.Written("kamayoq_2026-10-29_18-40.json"))

        assertEquals(1, started)
        assertFalse(backups.running)
        assertEquals(BackupResult.Written("kamayoq_2026-10-29_18-40.json"), backups.result)
    }
}
