package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import app.kamayoq.core.backup.BackupFormat
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.data.kavita.KavitaClient
import app.kamayoq.data.kavita.StoredKavitaKey
import java.io.File
import java.security.ProviderException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KavitaAccessTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado a chave do Kavita, então ela fica na pasta que o Android nunca copia`() {
        assertEquals(app.noBackupFilesDir, app.kavita.keyFile.parentFile)
    }

    private val secret: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val connected: (String, String) -> KavitaConnection = { _, _ -> KavitaConnection.Connected("0.9.1.4") }

    @Test
    fun `dado um backup exportado depois de conectar, quando leio o arquivo, então a chave não está nele`() {
        val access = KavitaAccess(app, connected) { secret }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"))

        sync.connect("https://a.example", "key-of-a-1234")

        val text = BackupFormat.write(app.store.backup("r1", Instant.EPOCH))
        assertTrue("https://a.example" in text)
        assertFalse("key-of-a-1234" in text)
    }

    private fun sync(access: KavitaAccess) = KavitaSync(app.store, access, File(app.cacheDir, "kavita"))

    @Test
    fun `dado uma chave de outra conta recusada, quando conecto, então a chave e o endereço antigos continuam`() {
        sync(KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4", accountId = 1) }) { secret })
            .connect("https://a.example", "key-of-a-1234")
        val other = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4", accountId = 2) }) { secret }

        sync(other).connect("https://a.example", "key-of-b-5678")

        assertEquals(StoredKavitaKey.Present("key-of-a-1234"), other.storedKey())
    }

    @Test
    fun `dado uma falha ao guardar a chave nova, quando conecto a outro endereço, então o servidor guardado não muda`() {
        var failing = false
        val access = KavitaAccess(app, connected) { if (failing) throw ProviderException("Keystore failed") else secret }
        sync(access).connect("https://a.example", "key-of-a-1234")

        failing = true
        sync(access).connect("https://b.example", "key-of-b-5678")
        failing = false

        assertEquals("https://a.example", app.store.kavitaServer()?.address)
        assertEquals(StoredKavitaKey.Present("key-of-a-1234"), access.storedKey())
    }

    @Test
    fun `dado outro endereço com bibliotecas de outros nomes, quando conecto, então recusa como outro servidor`() {
        val access = KavitaAccess(app, connected) { secret }
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String) = listOf(RemoteLibrary(2, "Outra coisa", 0))
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0)))
        app.store.markLibraries(checkNotNull(app.store.kavitaServer()).id, listOf(2))

        val answer = sync.connect("https://b.example", "key-of-a-1234")

        assertEquals(KavitaConnection.OtherAccount, answer)
        assertEquals("https://a.example", app.store.kavitaServer()?.address)
    }

    @Test
    fun `dado o mesmo endereço com bibliotecas de outros nomes, quando conecto, então recusa como outro servidor`() {
        val access = KavitaAccess(app, connected) { secret }
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String) = listOf(RemoteLibrary(2, "Outra coisa", 0))
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0)))

        assertEquals(KavitaConnection.OtherAccount, sync.connect("https://a.example", "key-of-b-5678"))
    }

    @Test
    fun `dado uma das bibliotecas renomeada e a outra igual, quando conecto a outro endereço, então é o mesmo servidor`() {
        val access = KavitaAccess(app, connected) { secret }
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String) =
                listOf(RemoteLibrary(2, "Quadrinhos", 0), RemoteLibrary(3, "Livros", 2))
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0), RemoteLibrary(3, "Livros", 2)))

        sync.connect("https://b.example", "key-of-a-1234")

        assertEquals("https://b.example", app.store.kavitaServer()?.address)
    }

    @Test
    fun `dado nenhuma biblioteca marcada agora, quando lembro, então as de antes continuam para conferir o servidor`() {
        val access = KavitaAccess(app, connected) { secret }
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0)))

        access.rememberLibraries(emptyList())

        assertEquals(mapOf(2 to "Mangás"), access.rememberedLibraries())
    }

    @Test
    fun `dado uma importação em andamento, quando conecto, então a conexão espera, para endereço e chave não se misturarem`() {
        val attempted = CountDownLatch(1)
        var calls = 0
        val access = KavitaAccess(app, { _, _ ->
            if (++calls > 1) attempted.countDown()
            KavitaConnection.Connected("0.9.1.4")
        }) { secret }
        val importing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String): List<RemoteLibrary> {
                importing.countDown()
                finish.await()
                return emptyList()
            }
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        var importFailure: Throwable? = null
        val running = thread { importFailure = runCatching { sync.importNow() }.exceptionOrNull() }
        importing.await()

        val connecting = thread { sync.connect("https://b.example", "key-of-b-5678") }
        // The new connection reached the server and now waits on the lock to keep its pair.
        attempted.await()
        while (connecting.state != Thread.State.WAITING && connecting.state != Thread.State.TIMED_WAITING) {
            check(connecting.isAlive) { "The connection did not wait" }
            Thread.sleep(5)
        }

        assertEquals("https://a.example", app.store.kavitaServer()?.address)
        finish.countDown()
        running.join()
        connecting.join()
        assertEquals(null, importFailure)
        assertEquals("https://b.example", app.store.kavitaServer()?.address)
    }

    @Test
    fun `dado uma conexão antiga que termina depois da nova, então o servidor guardado é o da nova`() {
        val oldAsked = CountDownLatch(1)
        val oldMayFinish = CountDownLatch(1)
        val access = KavitaAccess(app, { address, _ ->
            if (address == "https://a.example") {
                oldAsked.countDown()
                oldMayFinish.await()
            }
            KavitaConnection.Connected("0.9.1.4")
        }) { secret }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"))

        val older = thread { sync.connect("https://a.example", "key-of-a-1234") }
        oldAsked.await()
        sync.connect("https://b.example", "key-of-b-5678")
        oldMayFinish.countDown()
        older.join()

        assertEquals("https://b.example", app.store.kavitaServer()?.address)
        assertEquals(StoredKavitaKey.Present("key-of-b-5678"), access.storedKey())
    }

    @Test
    fun `dado duas conexões ao mesmo endereço fora de ordem, quando a antiga já passou pela recusa, então a conta guardada é a da chave guardada`() {
        val oldChecked = CountDownLatch(1)
        val oldMayFinish = CountDownLatch(1)
        val access = KavitaAccess(app, { _, key ->
            KavitaConnection.Connected("0.9.1.4", accountId = if (key == "key-of-a-1234") 1 else 2)
        }) { secret }
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String): List<RemoteLibrary> {
                if (key == "key-of-a-1234") {
                    oldChecked.countDown()
                    oldMayFinish.await()
                }
                return listOf(RemoteLibrary(2, "Mangás", 0))
            }
        }
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0)))
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)

        val older = thread { sync.connect("https://a.example", "key-of-a-1234") }
        oldChecked.await()
        sync.connect("https://a.example", "key-of-b-5678")
        oldMayFinish.countDown()
        older.join()

        assertEquals(StoredKavitaKey.Present("key-of-b-5678"), access.storedKey())
        assertTrue(app.store.acceptsKavitaAccount(2))
        assertFalse(app.store.acceptsKavitaAccount(1))
    }

    @Test
    fun `dado a chave nova guardada e o app morto antes de ligar o servidor, quando importo, então nenhum pedido sai`() {
        val access = KavitaAccess(app, connected) { secret }
        val asked = mutableListOf<String>()
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String): List<RemoteLibrary> {
                asked += address
                return emptyList()
            }
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        // What a process killed between keeping the key and linking the server leaves behind.
        access.connect("https://b.example", "key-of-b-5678")

        assertThrows(IOException::class.java) { sync.importNow() }
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `dado a chave nova guardada e o app morto antes do endereço, quando a tela de conexão abre, então o endereço é o da chave`() {
        val access = KavitaAccess(app, connected) { secret }
        access.connect("https://b.example", "key-of-b-5678")
        // What a process killed between the key file and the preferences leaves behind.
        app.getSharedPreferences("kavita", android.content.Context.MODE_PRIVATE).edit()
            .putString("address", "https://a.example").commit()

        assertEquals(StoredKavitaKey.Present("key-of-b-5678") to "https://b.example", access.savedForm())
    }

    @Test
    fun `dado outro endereço com os mesmos nomes de bibliotecas, quando conecto, então o servidor só muda de endereço`() {
        val access = KavitaAccess(app, connected) { secret }
        val client = object : KavitaClient() {
            override fun libraries(address: String, key: String) = listOf(RemoteLibrary(2, "Mangás", 0))
        }
        val sync = KavitaSync(app.store, access, File(app.cacheDir, "kavita"), client)
        sync.connect("https://a.example", "key-of-a-1234")
        access.rememberLibraries(listOf(RemoteLibrary(2, "Mangás", 0)))
        app.store.markLibraries(checkNotNull(app.store.kavitaServer()).id, listOf(2))

        sync.connect("https://b.example", "key-of-a-1234")

        assertEquals("https://b.example", app.store.kavitaServer()?.address)
    }

    @Test
    fun `dado uma chave de outra conta, quando conecto, então a tela recebe o aviso`() {
        val access = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4", accountId = 1) }) { secret }
        val other = KavitaAccess(app, { _, _ -> KavitaConnection.Connected("0.9.1.4", accountId = 2) }) { secret }
        KavitaSync(app.store, access, File(app.cacheDir, "kavita")).connect("https://a.example", "key-of-a-1234")

        val answer = KavitaSync(app.store, other, File(app.cacheDir, "kavita")).connect("https://a.example", "key-of-b-5678")

        assertEquals(KavitaConnection.OtherAccount, answer)
    }

    @Test
    fun `dado um servidor guardado, quando a chave de outro não pode ser guardada, então o par antigo continua`() {
        var failing = false
        val access = KavitaAccess(app, connected) { if (failing) throw ProviderException("Keystore failed") else secret }
        access.connect("https://a.example", "key-of-a-1234")

        failing = true
        val answer = access.connect("https://b.example", "key-of-b-5678")
        failing = false

        assertFalse((answer as KavitaConnection.Connected).keySaved)
        assertEquals("https://a.example", access.savedAddress)
        assertEquals(StoredKavitaKey.Present("key-of-a-1234"), access.storedKey())
    }

    @Test
    fun `dado uma conexão antiga que termina depois da nova, então fica o que a nova guardou`() {
        val oldStarted = CountDownLatch(1)
        val oldMayEnd = CountDownLatch(1)
        val access = KavitaAccess(app, { address, _ ->
            if (address == "https://old.example") {
                oldStarted.countDown()
                oldMayEnd.await()
            }
            KavitaConnection.Connected("0.9.1.4")
        }) { secret }

        val old = thread { access.connect("https://old.example", "old-key-1234") }
        oldStarted.await()
        access.connect("https://new.example", "new-key-5678")
        oldMayEnd.countDown()
        old.join()

        assertEquals("https://new.example", access.savedAddress)
        assertEquals(StoredKavitaKey.Present("new-key-5678"), access.storedKey())
    }
}
