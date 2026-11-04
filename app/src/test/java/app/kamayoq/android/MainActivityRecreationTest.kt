package app.kamayoq.android

import androidx.test.core.app.launchActivity
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityRecreationTest {

    @Test
    fun `dado a tela recriada, então o abridor de capítulos e o backup são os mesmos de antes`() {
        launchActivity<MainActivity>().use { scenario ->
            lateinit var opening: ChapterOpening
            lateinit var backups: Backups
            scenario.onActivity {
                opening = it.opening
                backups = it.backups
            }

            // What turning the phone does (developer.android.com, "Test your app's activities").
            scenario.recreate()

            scenario.onActivity {
                assertSame(opening, it.opening)
                assertSame(backups, it.backups)
            }
        }
    }
}
