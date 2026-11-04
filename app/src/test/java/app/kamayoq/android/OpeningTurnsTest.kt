package app.kamayoq.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningTurnsTest {
    @Test
    fun `dado uma abertura antiga cancelada no meio, quando a nova começa, então ela só roda depois de a antiga terminar`() = runBlocking {
        val turns = OpeningTurns()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val oldStarted = CountDownLatch(1)
        val oldMayEnd = CountDownLatch(1)

        val old = launch(Dispatchers.Default) {
            turns.open {
                events += "antiga começa"
                oldStarted.countDown()
                oldMayEnd.await(5, TimeUnit.SECONDS)
                events += "antiga termina"
            }
        }
        oldStarted.await()
        val cancelling = launch(Dispatchers.Default) { old.cancelAndJoin() }
        val new = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { turns.open { events += "nova" } }
        Thread.sleep(100)
        oldMayEnd.countDown()
        new.await()
        cancelling.join()

        assertEquals(listOf("antiga começa", "antiga termina", "nova"), events.toList())
    }

    @Test
    fun `dado uma abertura nova, quando chega a gravação da posição de uma abertura antiga, então ela não grava`() = runBlocking {
        // Page 3 of a screen that left must not land on top of page 5 of the newer one.
        val turns = OpeningTurns()
        val old = turns.open { it }
        val new = turns.open { it }
        var oldSaved = false
        var newSaved = false

        turns.ifLatest(old) { oldSaved = true }
        turns.ifLatest(new) { newSaved = true }

        assertFalse(oldSaved)
        assertTrue(newSaved)
    }

    @Test
    fun `dado uma gravação da posição em andamento, quando uma abertura nova começa, então ela só roda depois da gravação`() = runBlocking {
        val turns = OpeningTurns()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val saveStarted = CountDownLatch(1)
        val saveMayEnd = CountDownLatch(1)
        val round = turns.open { it }

        val saving = launch(Dispatchers.Default) {
            turns.ifLatest(round) {
                events += "gravação começa"
                saveStarted.countDown()
                saveMayEnd.await(5, TimeUnit.SECONDS)
                events += "gravação termina"
            }
        }
        saveStarted.await()
        val new = async(Dispatchers.Default) { turns.open { events += "nova" } }
        Thread.sleep(100)
        saveMayEnd.countDown()
        new.await()
        saving.join()

        assertEquals(listOf("gravação começa", "gravação termina", "nova"), events.toList())
    }

    @Test
    fun `dado uma gravação pedida que ainda espera a vez, quando a tela sai, então ela grava antes do fechamento`() = runBlocking {
        // Leaving cancels the screen; the last page it saw must still be saved, or the chapter
        // reopens on an older one.
        val turns = OpeningTurns()
        val round = turns.open { it }
        val busy = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        val holder = launch(Dispatchers.Default) {
            turns.ifLatest(round) {
                busy.countDown()
                mayEnd.await(5, TimeUnit.SECONDS)
            }
        }
        busy.await()

        val screen = launch(Dispatchers.Default) { turns.ifLatest(round) { saved.set(true) } }
        Thread.sleep(100)
        screen.cancelAndJoin()
        mayEnd.countDown()
        holder.join()
        turns.waitForSaves()

        assertTrue(saved.get())
    }

    @Test
    fun `dado a trava livre, quando uma gravação é pedida, então ela roda fora do fio de quem pediu`() = runBlocking {
        // The reader asks from the main thread: a database write there stalls the scrolling.
        val turns = OpeningTurns()
        val round = turns.open { it }
        val caller = Thread.currentThread()
        var ran: Thread? = null

        turns.ifLatest(round) { ran = Thread.currentThread() }

        assertNotSame(caller, ran)
    }
}
