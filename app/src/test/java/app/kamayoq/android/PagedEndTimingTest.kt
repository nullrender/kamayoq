package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ImageMode
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

// Gemini's review of slice 6, finding 2: a turn still moving when the person acts is a turn made.
@RunWith(RobolectricTestRunner::class)
class PagedEndTimingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun saves() = ShadowLog.getLogsForTag("Kamayoq").count { it.msg == "Saving the reading position failed" }

    @Test
    fun `dado a virada para o bloco de fim ainda em andamento, quando toco em Voltar à obra, então o fim é gravado`() {
        // Each save fails here (the chapter is not in the store), so the log counts the saves asked for.
        val chapter = OpenedChapter("chapter", listOf(PageImage(100, 150) { ByteArrayInputStream(ByteArray(0)) }), ChapterProgress.NEVER_OPENED) {}
        val turns = OpeningTurns()
        val round = runBlocking { turns.open { it } }
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                ImageReader(
                    app.store, chapter, turns, round, "Obra · Cap. 1", null, "test-device", detectedAs(ImageMode.PAGES_LEFT_TO_RIGHT),
                    onNext = {}, onBack = { shown = false },
                )
            }
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        // Part of the way: the end block is on screen and the pager has not settled on it.
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithText(app.getString(R.string.reader_back_to_work)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        runBlocking { turns.waitForSaves() }

        assertTrue("Saves asked: ${saves()}", saves() >= 1)
    }

    @Test
    fun `dado uma virada ainda em andamento, quando o leitor sai, então a página de destino é gravada`() {
        val chapter = OpenedChapter("chapter", List(3) { PageImage(100, 150) { ByteArrayInputStream(ByteArray(0)) } }, ChapterProgress.NEVER_OPENED) {}
        val turns = OpeningTurns()
        val round = runBlocking { turns.open { it } }
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) ImageReader(app.store, chapter, turns, round, "Obra · Cap. 1", null, "test-device", detectedAs(ImageMode.PAGES_LEFT_TO_RIGHT), {}, {})
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.mainClock.advanceTimeBy(120)
        shown = false
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        runBlocking { turns.waitForSaves() }

        assertTrue("Saves asked: ${saves()}", saves() >= 1)
    }
}
