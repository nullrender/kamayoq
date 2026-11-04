package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.data.kavita.StoredKavitaKey
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KavitaScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()
    private val calls = mutableListOf<Pair<String, String>>()

    private class Choice(private val fails: Boolean = false, private val alreadyMarked: Set<Int> = emptySet()) : KavitaLibraryChoice {
        var included: Set<Int>? = null

        override suspend fun load(): List<RemoteLibrary> {
            if (fails) throw IOException("No answer")
            return listOf(RemoteLibrary(2, "Mangás", 0), RemoteLibrary(3, "Livros", 2))
        }

        override suspend fun marked(): Set<Int> = alreadyMarked

        override suspend fun include(libraries: Set<Int>) {
            included = libraries
        }
    }

    private fun showConnected(choice: KavitaLibraryChoice) {
        compose.setContent {
            KavitaScreen(
                initialAddress = "https://a.example",
                storedKey = StoredKavitaKey.Present("key-of-a-1234"),
                connect = { _, _ -> KavitaConnection.Connected("0.9.1.4") },
                onBack = {},
                libraries = choice,
            )
        }
        compose.onNodeWithText(app.getString(R.string.kavita_connect_button)).performClick()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `dado a conexão feita, então as bibliotecas aparecem sem nada marcado`() {
        showConnected(Choice())

        waitForText("Mangás")
        compose.onNodeWithText("Mangás").assertIsOff()
        compose.onNodeWithText("Livros").assertIsOff()
        compose.onNodeWithText(app.getString(R.string.kavita_include)).assertIsNotEnabled()
    }

    @Test
    fun `dado uma biblioteca marcada, quando incluo, então só ela vai e a tela confirma`() {
        val choice = Choice()
        showConnected(choice)
        waitForText("Mangás")

        compose.onNodeWithText("Mangás").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.kavita_include)).performScrollTo().performClick()

        waitForText(app.getString(R.string.kavita_included))
        assertEquals(setOf(2), choice.included)
    }

    @Test
    fun `dado bibliotecas marcadas, quando giro o aparelho, então a lista e as marcas continuam`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            KavitaScreen(
                initialAddress = "https://a.example",
                storedKey = StoredKavitaKey.Present("key-of-a-1234"),
                connect = { _, _ -> KavitaConnection.Connected("0.9.1.4") },
                onBack = {},
                libraries = remember { Choice() },
            )
        }
        compose.onNodeWithText(app.getString(R.string.kavita_connect_button)).performClick()
        waitForText("Mangás")
        compose.onNodeWithText("Mangás").performScrollTo().performClick()

        restoration.emulateSavedInstanceStateRestore()

        waitForText("Mangás")
        compose.onNodeWithText("Mangás").assertIsOn()
    }

    @Test
    fun `dado o link OPDS colado no endereço, então a chave dele sai do campo na hora`() {
        compose.setContent {
            KavitaScreen("", StoredKavitaKey.Absent, { _, _ -> KavitaConnection.Refused }, {})
        }

        compose.onNodeWithText(app.getString(R.string.kavita_address)).performTextInput("https://a.example/api/opds/segredo-1234")

        compose.onNodeWithText("segredo-1234", substring = true).assertDoesNotExist()
    }

    @Test
    fun `dado a última biblioteca desmarcada, quando confirmo, então nenhuma fica marcada`() {
        val choice = Choice(alreadyMarked = setOf(2))
        showConnected(choice)
        waitForText("Mangás")

        compose.onNodeWithText("Mangás").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.kavita_include)).performScrollTo().performClick()

        waitForText(app.getString(R.string.kavita_included))
        assertEquals(emptySet<Int>(), choice.included)
    }

    @Test
    fun `dado uma chave que o aparelho não guardou, então as bibliotecas não aparecem`() {
        compose.setContent {
            KavitaScreen(
                initialAddress = "https://a.example",
                storedKey = StoredKavitaKey.Present("key-of-a-1234"),
                connect = { _, _ -> KavitaConnection.Connected("0.9.1.4", keySaved = false) },
                onBack = {},
                libraries = Choice(),
            )
        }
        compose.onNodeWithText(app.getString(R.string.kavita_connect_button)).performClick()

        waitForText(app.getString(R.string.kavita_key_not_saved))
        compose.onNodeWithText("Mangás").assertDoesNotExist()
    }

    @Test
    fun `dado uma falha ao listar as bibliotecas, então a tela diz isso`() {
        showConnected(Choice(fails = true))

        waitForText(app.getString(R.string.kavita_libraries_failed))
    }

    @Test
    fun `dado uma chave de outra conta, então a tela avisa`() {
        show(KavitaConnection.OtherAccount, initialAddress = "https://a.example", storedKey = StoredKavitaKey.Present("key-of-a-1234"))

        compose.onNodeWithText(app.getString(R.string.kavita_connect_button)).performClick()

        waitForText(app.getString(R.string.kavita_other_account))
    }

    private fun show(
        result: KavitaConnection,
        initialAddress: String = "",
        storedKey: StoredKavitaKey = StoredKavitaKey.Absent,
    ) = compose.setContent {
        KavitaScreen(
            initialAddress = initialAddress,
            storedKey = storedKey,
            connect = { address, key ->
                calls += address to key
                result
            },
            onBack = {},
        )
    }

    private fun fill(address: String, key: String) {
        compose.onNodeWithText(app.getString(R.string.kavita_address)).performTextInput(address)
        compose.onNodeWithText(app.getString(R.string.kavita_key)).performTextInput(key)
    }

    private fun connectButton() = compose.onNodeWithText(app.getString(R.string.kavita_connect_button))

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `dado o resultado Recusado, quando a tela o recebe, então mostra o aviso e a chave continua no campo`() {
        show(KavitaConnection.Refused)
        fill("https://kavita.example", "invented-key-1234")

        connectButton().performClick()
        waitFor(app.getString(R.string.kavita_refused))
        connectButton().performClick()

        compose.waitUntil(10_000) { calls.size == 2 }
        assertEquals(calls[0], calls[1])
    }

    @Test
    fun `dado o resultado Inacessível, quando a tela o recebe, então mostra o endereço que não respondeu`() {
        show(KavitaConnection.Unreachable)
        fill("https://kavita.example", "invented-key-1234")

        connectButton().performClick()

        waitFor(app.getString(R.string.kavita_unreachable, "https://kavita.example"))
    }

    @Test
    fun `dado o resultado Não é Kavita, quando a tela o recebe, então pede para conferir o endereço`() {
        show(KavitaConnection.NotKavita)
        fill("https://kavita.example", "invented-key-1234")

        connectButton().performClick()

        waitFor(app.getString(R.string.kavita_not_kavita, "https://kavita.example"))
    }

    @Test
    fun `dado o endereço OPDS colado, quando conecto, então vai só o endereço do servidor`() {
        show(KavitaConnection.Refused)
        fill("http://kavita.example:5000/api/opds/key-1234", "invented-key-1234")

        connectButton().performClick()

        compose.waitUntil(10_000) { calls.isNotEmpty() }
        assertEquals("http://kavita.example:5000", calls.single().first)
    }

    @Test
    fun `dado o resultado Conectado, quando a tela o recebe, então mostra a versão do Kavita`() {
        show(KavitaConnection.Connected("0.9.1.4"))
        fill("https://kavita.example", "invented-key-1234")

        connectButton().performClick()

        waitFor(app.getString(R.string.kavita_connected, "0.9.1.4"))
    }

    @Test
    fun `dado uma conexão cuja chave não pôde ser guardada, quando a tela a recebe, então avisa`() {
        show(KavitaConnection.Connected("0.9.1.4", keySaved = false))
        fill("https://kavita.example", "invented-key-1234")

        connectButton().performClick()

        waitFor(app.getString(R.string.kavita_key_not_saved))
    }

    @Test
    fun `dado a tela aberta, quando giro o aparelho, então o que digitei continua`() {
        val restoration = StateRestorationTester(compose)
        // The app keeps address and key out of the saved state, in an object that outlives the screen.
        val keyDraft = mutableStateOf("")
        val addressDraft = mutableStateOf("")
        restoration.setContent {
            KavitaScreen("", StoredKavitaKey.Absent, { address, key ->
                calls += address to key
                KavitaConnection.Refused
            }, {}, keyDraft, addressDraft = addressDraft)
        }
        fill("https://kavita.example", "invented-key-1234")

        restoration.emulateSavedInstanceStateRestore()
        connectButton().performClick()

        compose.waitUntil(10_000) { calls.isNotEmpty() }
        assertEquals("https://kavita.example" to "invented-key-1234", calls.single())
    }

    @Test
    fun `dado uma chave digitada, quando a tela guarda seu estado, então a chave não vai junto`() {
        val registry = SaveableStateRegistry(null) { true }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                KavitaScreen("", StoredKavitaKey.Absent, { _, _ -> KavitaConnection.Refused }, {})
            }
        }
        fill("https://kavita.example", "invented-key-1234")

        compose.runOnIdle { assertFalse(registry.performSave().toString().contains("invented-key-1234")) }
    }

    @Test
    fun `dado um endereço digitado, quando a tela guarda seu estado, então ele não vai junto, para nunca voltar sem a sua chave`() {
        val registry = SaveableStateRegistry(null) { true }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                KavitaScreen("", StoredKavitaKey.Absent, { _, _ -> KavitaConnection.Refused }, {})
            }
        }
        fill("https://kavita.example", "invented-key-1234")

        compose.runOnIdle { assertFalse(registry.performSave().toString().contains("kavita.example")) }
    }

    @Test
    fun `dado um endereço http, quando ele é digitado, então a tela avisa que não há criptografia`() {
        show(KavitaConnection.Refused)

        fill("http://kavita.example", "")

        waitFor(app.getString(R.string.kavita_http_warning))
    }

    @Test
    fun `dado um endereço https, quando ele é digitado, então não há aviso de criptografia`() {
        show(KavitaConnection.Refused)

        fill("https://kavita.example", "")

        compose.waitForIdle()
        val warning = hasText(app.getString(R.string.kavita_http_warning))
        assertTrue(compose.onAllNodes(warning).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `dado uma chave guardada que não abre, quando a tela aparece, então pede a chave de novo`() {
        show(KavitaConnection.Refused, storedKey = StoredKavitaKey.Unreadable)

        waitFor(app.getString(R.string.kavita_key_unreadable))
    }

    @Test
    fun `dado um endereço e uma chave guardados, quando conecto, então eles são usados sem digitar`() {
        show(
            KavitaConnection.Connected("0.9.1.4"),
            initialAddress = "https://kavita.example",
            storedKey = StoredKavitaKey.Present("invented-key-1234"),
        )

        connectButton().performClick()

        compose.waitUntil(10_000) { calls.isNotEmpty() }
        assertEquals("https://kavita.example" to "invented-key-1234", calls.single())
    }

    @Test
    fun `dado um campo vazio, então Conectar não está disponível`() {
        show(KavitaConnection.Refused)

        fill("https://kavita.example", "")
        connectButton().assertIsNotEnabled()

        compose.onNodeWithText(app.getString(R.string.kavita_key)).performTextInput("invented-key-1234")
        connectButton().assertIsEnabled()
    }
}
