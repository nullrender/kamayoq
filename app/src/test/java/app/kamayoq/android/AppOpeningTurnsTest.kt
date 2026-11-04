package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppOpeningTurnsTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado uma tela que saiu no meio de uma abertura, quando outra tela abre um capítulo, então ela espera a antiga terminar`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val oldStarted = CountDownLatch(1)
        val oldMayEnd = CountDownLatch(1)
        // Each screen asks the app for its turns, as ReaderHost does.
        val firstScreen = app.opening.turns
        val secondScreen = app.opening.turns

        val old = launch(Dispatchers.Default) {
            firstScreen.open {
                events += "antiga começa"
                oldStarted.countDown()
                oldMayEnd.await(5, TimeUnit.SECONDS)
                events += "antiga termina"
            }
        }
        oldStarted.await()
        old.cancel()
        val new = async(Dispatchers.Default) { secondScreen.open { events += "nova" } }
        Thread.sleep(100)
        oldMayEnd.countDown()
        new.await()
        old.join()

        assertEquals(listOf("antiga começa", "antiga termina", "nova"), events.toList())
    }
}
