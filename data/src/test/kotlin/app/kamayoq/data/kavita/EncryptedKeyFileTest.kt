package app.kamayoq.data.kavita

import java.io.File
import java.nio.file.Files
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedKeyFileTest {
    private val file = File(Files.createTempDirectory("kavita-key").toFile(), "kavita-key")

    private fun newSecret(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `dado uma chave guardada, quando leio, então ela volta igual e o arquivo não a mostra`() {
        val secret = newSecret()
        val keys = EncryptedKeyFile(file) { secret }

        keys.save("invented-key-1234")

        assertEquals(StoredKavitaKey.Present("invented-key-1234"), keys.read())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("invented-key-1234"))
    }

    @Test
    fun `dado nenhuma chave guardada, quando leio, então não há chave`() {
        assertEquals(StoredKavitaKey.Absent, EncryptedKeyFile(file) { newSecret() }.read())
    }

    @Test
    fun `dado uma chave cifrada que o aparelho não abre mais, quando leio, então ela é ilegível e o arquivo fica`() {
        EncryptedKeyFile(file, newSecret().let { { it } }).save("invented-key-1234")
        val saved = file.readBytes()

        assertEquals(StoredKavitaKey.Unreadable, EncryptedKeyFile(file) { newSecret() }.read())
        assertTrue(saved.contentEquals(file.readBytes()))
    }

    @Test
    fun `dado um arquivo cortado, quando leio, então a chave é ilegível`() {
        file.writeBytes(ByteArray(3))

        assertEquals(StoredKavitaKey.Unreadable, EncryptedKeyFile(file) { newSecret() }.read())
    }

    @Test
    fun `dado uma falha do cofre do aparelho ao abrir, quando leio, então a chave é ilegível e o arquivo fica`() {
        val secret = newSecret()
        EncryptedKeyFile(file) { secret }.save("invented-key-1234")

        val failing = EncryptedKeyFile(file) { throw ProviderException("Keystore failed") }

        assertEquals(StoredKavitaKey.Unreadable, failing.read())
        assertTrue(file.exists())
    }

    @Test
    fun `dado uma gravação que não termina, quando leio, então a chave anterior continua`() {
        val secret = newSecret()
        val keys = EncryptedKeyFile(file) { secret }
        keys.save("invented-key-1234")

        // A directory where the draft goes makes the write fail midway, as a full disk would.
        File(file.path + ".draft").mkdir()
        runCatching { keys.save("other-key-5678") }

        assertEquals(StoredKavitaKey.Present("invented-key-1234"), keys.read())
    }
}
