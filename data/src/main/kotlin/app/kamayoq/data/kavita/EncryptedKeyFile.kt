package app.kamayoq.data.kavita

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface StoredKavitaKey {
    data object Absent : StoredKavitaKey

    data class Present(val key: String) : StoredKavitaKey

    /** The file is there and cannot be opened; the person types the key again. */
    data object Unreadable : StoredKavitaKey
}

/**
 * Holds only the ciphertext, with its IV in front. A file that cannot be opened stays as it is: a
 * failure here must not cost the key silently (Kover loses it that way; docs/decisions/kavita-step-3-2026-10-30.md).
 */
class EncryptedKeyFile(private val file: File, private val secretKey: () -> SecretKey) {
    fun save(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        // Written aside and moved in one step: a write that stops midway keeps the key already saved.
        val draft = File(file.path + ".draft")
        draft.writeBytes(cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8)))
        Files.move(draft.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(): StoredKavitaKey {
        if (!file.exists()) return StoredKavitaKey.Absent
        return try {
            val saved = file.readBytes()
            if (saved.size <= IV_BYTES) return StoredKavitaKey.Unreadable
            val iv = GCMParameterSpec(TAG_BITS, saved, 0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), iv) }
            StoredKavitaKey.Present(String(cipher.doFinal(saved, IV_BYTES, saved.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            StoredKavitaKey.Unreadable
        } catch (_: ProviderException) {
            StoredKavitaKey.Unreadable
        } catch (_: IOException) {
            StoredKavitaKey.Unreadable
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
