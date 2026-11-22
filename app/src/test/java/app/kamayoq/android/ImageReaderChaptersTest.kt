package app.kamayoq.android

import androidx.activity.ComponentActivity
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 6 of the v1 interface: the work's chapters in the image reader (FEATURES, part 5).
@RunWith(RobolectricTestRunner::class)
class ImageReaderChaptersTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado os controles, quando toco em Capítulos e escolho o capítulo 3, então ele é aberto sem sair do leitor`() {
        val chapter = OpenedChapter("c2", List(3) { PageImage(100, 300) { ByteArrayInputStream(ByteArray(0)) } }, ChapterProgress.NEVER_OPENED) {}
        val rows = listOf(
            ReaderChapterRow("c1", "Capítulo 1", read = true, percent = null),
            ReaderChapterRow("c2", "Capítulo 2", read = false, percent = 40),
            ReaderChapterRow("c3", "Capítulo 3", read = false, percent = null),
        )
        var opened: String? = null
        compose.setContent {
            ImageReader(
                app.store, chapter, OpeningTurns(), 0, "Teto x Egen · Capítulo 2", "Capítulo 3", "test-device", detectedAs(ImageMode.STRIP), {}, {},
                chapterRows = rows,
                onOpenChapter = { opened = it },
            )
        }
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()

        compose.onNodeWithText(app.getString(R.string.reader_chapters)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.reader_chapter_reading, 40)).assertExists()
        compose.onNodeWithText("Capítulo 3").performClick()
        compose.waitForIdle()

        assertEquals("c3", opened)
    }
}
