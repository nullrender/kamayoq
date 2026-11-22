package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.data.HomeContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Slice 4 of the v1 interface: what an empty Home shows before and after the Library's rows are read.
@RunWith(RobolectricTestRunner::class)
class HomeEmptyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private val nothing = HomeContent(emptyList(), emptyList(), emptyList())

    private fun show(hasWorks: Boolean?, canRestore: Boolean) {
        compose.setContent {
            KamayoqTheme {
                HomeScreen(
                    firstOpening = false,
                    restoreBusy = false,
                    onChooseFolder = {},
                    onOpenKavita = {},
                    onPickBackup = {},
                    content = nothing,
                    hasWorks = hasWorks,
                    canRestore = canRestore,
                    onOpenLibrary = {},
                    onOpenWork = {},
                    onRead = { _, _ -> },
                    downloaded = emptySet(),
                    canDownload = { false },
                    onMarkRead = { _, _ -> },
                    onDownload = { _, _ -> },
                    notices = SnackbarHostState(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `dado o Início vazio e a Biblioteca ainda não lida, então ele não mostra a primeira abertura`() {
        show(hasWorks = null, canRestore = false)

        assertEquals(0, count(app.getString(R.string.empty_title)))
    }

    @Test
    fun `dado um Kavita conectado e nenhuma obra, então o Início mostra os caminhos de entrada sem Restaurar backup`() {
        show(hasWorks = false, canRestore = false)

        assertEquals(1, count(app.getString(R.string.empty_title)))
        assertEquals(0, count(app.getString(R.string.restore_backup)))
    }
}
