package app.kamayoq.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.data.kavita.EncryptedKeyFile
import app.kamayoq.data.kavita.KavitaClient
import app.kamayoq.data.kavita.StoredKavitaKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val TAG = "Kamayoq"
private const val KEYSTORE = "AndroidKeyStore"
private const val SECRET_ALIAS = "kavita-key"
private const val ADDRESS = "address"
private const val LIBRARIES = "libraries"

/**
 * The connection the person made. The key file stays in the folder Android never copies: the secret
 * that opens it does not travel with a backup or to a new phone (docs/decisions/kavita-step-3-2026-10-30.md).
 */
class KavitaAccess(
    context: Context,
    private val client: (address: String, key: String) -> KavitaConnection = KavitaClient()::connect,
    secret: (() -> SecretKey)? = null,
) {
    private val preferences = context.getSharedPreferences("kavita", Context.MODE_PRIVATE)
    internal val keyFile = File(context.noBackupFilesDir, "kavita-key")
    private val keys = EncryptedKeyFile(keyFile, secret ?: ::deviceSecret)

    // A connection is not stopped by leaving the screen: only the newest one may save.
    private val attempts = AtomicInteger()

    val savedAddress: String get() = preferences.getString(ADDRESS, "").orEmpty()

    /**
     * What is typed on the screen, here and not in its saved state, which leaves the process: in
     * memory only, it survives the phone turning.
     */
    val keyDraft = mutableStateOf("")

    /** The address typed, kept with [keyDraft]: after the process dies the two are gone together. */
    val addressDraft = mutableStateOf("")

    /** The key alone, for the screen; the file keeps the address it was made for in front of it. */
    @Synchronized
    fun storedKey(): StoredKavitaKey = when (val stored = keys.read()) {
        is StoredKavitaKey.Present -> StoredKavitaKey.Present(stored.key.substringAfter('\n'))
        else -> stored
    }

    /**
     * The key and the address saved with it, from one read of the file: two reads could straddle a
     * new pair being saved.
     */
    @Synchronized
    fun savedForm(): Pair<StoredKavitaKey, String> = when (val stored = keys.read()) {
        is StoredKavitaKey.Present -> {
            val text = stored.key
            if ('\n' in text) StoredKavitaKey.Present(text.substringAfter('\n')) to text.substringBefore('\n')
            else stored to savedAddress
        }
        else -> stored to savedAddress
    }

    /**
     * Address and key as saved together, in one file written in one step: a process killed between
     * two writes cannot leave a key paired with another address.
     * A file from before this kept only the key; its address is the saved one.
     */
    @Synchronized
    fun storedPair(): Pair<String, String>? {
        val stored = keys.read() as? StoredKavitaKey.Present ?: return null
        val text = stored.key
        return if ('\n' in text) text.substringBefore('\n') to text.substringAfter('\n') else savedAddress to text
    }

    /**
     * Nothing is kept until [refusal] looks at the answer: a refused connection must leave the saved
     * key and address as they were.
     */
    fun connect(
        address: String,
        key: String,
        refusal: (KavitaConnection.Connected) -> KavitaConnection? = { null },
        whileSaving: (save: () -> Pair<KavitaConnection, Boolean>) -> KavitaConnection = { it().first },
    ): KavitaConnection {
        val attempt = attempts.incrementAndGet()
        val connection = client(address, key)
        if (connection !is KavitaConnection.Connected) return connection
        refusal(connection)?.let { return it }
        return whileSaving { keep(attempt, connection, address, key) }
    }

    /** The answer, and whether this attempt's pair is the one kept. */
    private fun keep(attempt: Int, connection: KavitaConnection.Connected, address: String, key: String): Pair<KavitaConnection, Boolean> =
        synchronized(this) {
            if (attempts.get() != attempt) return@synchronized connection to false
            val answer = saved(connection, address, key)
            answer to ((answer as? KavitaConnection.Connected)?.keySaved == true)
        }

    // The key first: if it fails, the saved address keeps matching the saved key, and a key never
    // goes to a server it was not made for.
    private fun saved(connection: KavitaConnection.Connected, address: String, key: String): KavitaConnection =
        try {
            // A key never has a line break (checked by the client before connecting).
            keys.save("$address\n${key.trim()}")
            preferences.edit().putString(ADDRESS, address).apply()
            connection
        } catch (e: GeneralSecurityException) {
            notSaved(connection, e)
        } catch (e: ProviderException) {
            notSaved(connection, e)
        } catch (e: IOException) {
            notSaved(connection, e)
        }

    private fun notSaved(connection: KavitaConnection.Connected, cause: Exception): KavitaConnection {
        Log.e(TAG, "Saving the Kavita key failed", cause)
        return connection.copy(keySaved = false)
    }

    /**
     * The names of the libraries marked, which tell this server from another one. Nothing marked
     * now keeps the last ones: unmarking everything must not turn the check off.
     */
    fun rememberLibraries(libraries: List<RemoteLibrary>) {
        if (libraries.isEmpty()) return
        preferences.edit().putStringSet(LIBRARIES, libraries.map { "${it.id}:${it.name}" }.toSet()).apply()
    }

    /** By id; empty when nothing was marked yet. */
    fun rememberedLibraries(): Map<Int, String> = preferences.getStringSet(LIBRARIES, emptySet()).orEmpty()
        .associate { it.substringBefore(':').toInt() to it.substringAfter(':') }

    private fun deviceSecret(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return store.getKey(SECRET_ALIAS, null) as SecretKey? ?: newDeviceSecret()
    }

    private fun newDeviceSecret(): SecretKey {
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(SECRET_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
