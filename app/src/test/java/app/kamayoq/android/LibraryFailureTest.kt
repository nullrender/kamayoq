package app.kamayoq.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class NewerLibraryApp : KamayoqApp() {
    override fun openLibrary() = LibraryOpening.Failed(LibraryFailure.NEWER_VERSION)
}

class NotOpenedLibraryApp : KamayoqApp() {
    override fun openLibrary() = LibraryOpening.Failed(LibraryFailure.NOT_OPENED)
}

@RunWith(RobolectricTestRunner::class)
class LibraryFailureTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(application = NewerLibraryApp::class)
    fun `dado um banco de versão mais nova, quando o app abre, então pede atualização em vez de cair`() {
        compose.onNodeWithText("Update Kamayoq", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(application = NotOpenedLibraryApp::class)
    fun `dado uma biblioteca que não abre, quando o app abre, então diz que nada foi apagado em vez de cair`() {
        compose.onNodeWithText("Nothing was erased", substring = true).assertIsDisplayed()
    }
}
