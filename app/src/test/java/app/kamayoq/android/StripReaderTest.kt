package app.kamayoq.android

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.progress.ChapterProgress
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class StripReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val chapter = OpenedChapter(
        chapterId = "chapter",
        pages = listOf(PageImage(null, null) { ByteArrayInputStream(ByteArray(0)) }),
        progress = ChapterProgress.NEVER_OPENED,
        release = {},
    )

    @Test
    fun `dado o leitor com os controles escondidos, quando o TalkBack pede para mostrá-los, então o Voltar aparece`() {
        compose.setContent { StripReader(app.store, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", {}, {}) }

        // The tap has an action with a name.
        val label = app.getString(R.string.reader_show_controls)
        compose.onNode(SemanticsMatcher("has the action '$label'") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == label })
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText(app.getString(R.string.back)).assertExists()
    }

    @Test
    fun `dado uma página que falha com um erro que não é de leitura, então ela diz que a imagem não abre e o app continua`() {
        val broken = OpenedChapter("chapter", listOf(PageImage(100, 100) { error("The folder gave no stream") }), ChapterProgress.NEVER_OPENED) {}

        compose.setContent { StripReader(app.store, broken, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", {}, {}) }

        val message = app.getString(R.string.reader_page_failed)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `dado o leitor com o TalkBack, então cada página diz qual é, entre quantas`() {
        compose.setContent { StripReader(app.store, chapter, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", {}, {}) }

        compose.onNodeWithContentDescription(app.getString(R.string.reader_page_label, 1, 1)).assertExists()
    }

    /** Whether the screen stays on while [opened] is shown. */
    private fun keepsScreenOn(opened: OpenedChapter): Boolean {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            StripReader(app.store, opened, OpeningTurns(), 0, "Obra · Cap. 1", null, "test-device", {}, {})
        }
        compose.waitForIdle()
        return view.keepScreenOn
    }

    @Test
    fun `dado um capítulo sem imagens, então a mensagem não mantém a tela acesa`() {
        assertFalse(keepsScreenOn(OpenedChapter("chapter", emptyList(), ChapterProgress.NEVER_OPENED) {}))
    }

    @Test
    fun `dado um capítulo com imagens, então a tela fica acesa durante a leitura`() {
        assertTrue(keepsScreenOn(chapter))
    }

    @Test
    fun `dado uma gravação que espera a vez, quando a tela sai e a gravação falha, então a falha fica registrada`() {
        // The save outlives the screen; its failure must still reach the log. This chapter is not in
        // the store, so saving it fails.
        val turns = OpeningTurns()
        val round = runBlocking { turns.open { it } }
        val busy = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        val holder = thread { runBlocking { turns.ifLatest(round) { busy.countDown(); mayEnd.await(5, TimeUnit.SECONDS) } } }
        busy.await()
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) StripReader(app.store, chapter, turns, round, "Obra · Cap. 1", null, "test-device", {}, {}) }
        compose.waitForIdle()

        shown = false
        compose.waitForIdle()
        mayEnd.countDown()
        holder.join()
        runBlocking { turns.waitForSaves() }

        assertTrue(ShadowLog.getLogsForTag("Kamayoq").any { it.msg == "Saving the reading position failed" })
    }

    @Test
    fun `dado uma gravação que demora, quando a pessoa rola e sai, então a página nova também é pedida`() {
        // While one save waits, the reader must still ask for the next page it shows; otherwise
        // leaving then reopens on the older one. Each save fails here (the chapter is not in the
        // store), so the log counts the saves asked for.
        val tall = OpenedChapter(
            "chapter",
            List(3) { PageImage(100, 10_000) { ByteArrayInputStream(ByteArray(0)) } },
            ChapterProgress.NEVER_OPENED,
        ) {}
        val turns = OpeningTurns()
        val round = runBlocking { turns.open { it } }
        val busy = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        val holder = thread { runBlocking { turns.ifLatest(round) { busy.countDown(); mayEnd.await(5, TimeUnit.SECONDS) } } }
        busy.await()
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) StripReader(app.store, tall, turns, round, "Obra · Cap. 1", null, "test-device", {}, {}) }
        compose.waitForIdle()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        mayEnd.countDown()
        holder.join()
        runBlocking { turns.waitForSaves() }

        assertEquals(2, ShadowLog.getLogsForTag("Kamayoq").count { it.msg == "Saving the reading position failed" })
    }
}
