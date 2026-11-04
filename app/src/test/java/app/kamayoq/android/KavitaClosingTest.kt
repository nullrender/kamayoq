package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteProgress
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.data.kavita.KavitaClient
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KavitaClosingTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado uma gravação da posição em andamento, quando o leitor fecha, então a conferência com o Kavita espera a gravação`() = runBlocking {
        // Closing used to read page 0, take the server's page 2, and see the save of page 1 land
        // after it, to be sent over the server's later.
        val events = Collections.synchronizedList(mutableListOf<String>())
        val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4") }) { secret }
        val client = object : KavitaClient() {
            override fun progress(address: String, key: String, chapterId: Int): RemoteProgress {
                events += "consulta ao Kavita"
                return RemoteProgress(2, "2026-11-03T10:00:00")
            }
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "closing"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        val server = checkNotNull(app.store.kavitaServer()).id
        val chapter = ImportedChapter("91", "Cap. 1", UnitKind.CHAPTER, 1.0, ChapterFormat.ARCHIVE, "3|100", 3, "Cap. 1.cbz", 7)
        app.store.importKavita(server, listOf(ImportedWork("12", "Obra", WorkType.COMICS, null, 2, listOf(chapter))))
        val stored = app.store.chapters(app.store.works().single().id).single()
        val turns = OpeningTurns()
        val round = turns.open { it }
        val saveStarted = CountDownLatch(1)
        val saveMayEnd = CountDownLatch(1)

        val saving = launch(Dispatchers.Default) {
            turns.ifLatest(round) {
                events += "gravação começa"
                saveStarted.countDown()
                saveMayEnd.await(5, TimeUnit.SECONDS)
                app.store.saveProgress(stored.id, ChapterProgress(null, StripPosition(1, 3, Instant.now(), "test-device"), 0))
                events += "gravação termina"
            }
        }
        saveStarted.await()
        sync.finishReading(stored.id, token = 0, saves = turns)
        Thread.sleep(200)
        saveMayEnd.countDown()
        saving.join()
        val deadline = System.currentTimeMillis() + 10_000
        while ("consulta ao Kavita" !in events && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(listOf("gravação começa", "gravação termina", "consulta ao Kavita"), events.toList())
    }
}
