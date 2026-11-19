package app.kamayoq.data

import app.kamayoq.core.backup.Backup
import app.kamayoq.core.backup.BackupFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

sealed interface RestoreResult {
    /** [kavitaAddress] is the restored server's, whose key must be typed again; null with no server. */
    data class Restored(val kavitaAddress: String?) : RestoreResult

    /** Something is stored: in v1 a backup goes only into an empty app. */
    data object NotEmpty : RestoreResult

    /** The file is not a backup this version can read. */
    data class Unreadable(val cause: Exception) : RestoreResult

    /** The file could not be read. */
    data class ReadFailed(val cause: Exception) : RestoreResult

    /** The database refused the backup; nothing was written. */
    data class Failed(val cause: Exception) : RestoreResult
}

/** A backup file opened: [Ready] waits for the person's confirmation. */
sealed interface RestoreOpening {
    data class Ready(val backup: Backup) : RestoreOpening

    data class Refused(val result: RestoreResult) : RestoreOpening
}

/**
 * Restores a backup the person picked, on an empty app only (docs/decisions/backup-restore-2026-11-18.md):
 * [open] reads and checks the file, [apply] writes it once confirmed.
 */
class BackupRestore(private val store: LibraryStore) {

    /** Asks for the file only when the app is empty: a refusal costs no reading. */
    fun open(input: () -> InputStream): RestoreOpening {
        if (!store.isEmpty()) return RestoreOpening.Refused(RestoreResult.NotEmpty)
        val read = try {
            input().use(::readUpToCeiling)
        } catch (e: IOException) {
            return RestoreOpening.Refused(RestoreResult.ReadFailed(e))
        } catch (e: SecurityException) {
            // The provider took back the access the picker gave.
            return RestoreOpening.Refused(RestoreResult.ReadFailed(e))
        }
        val bytes = read ?: return RestoreOpening.Refused(
            RestoreResult.Unreadable(IllegalArgumentException("The file is over $MAX_BYTES bytes")),
        )
        return try {
            RestoreOpening.Ready(BackupFormat.read(bytes.toString(Charsets.UTF_8)))
        } catch (e: IllegalArgumentException) {
            RestoreOpening.Refused(RestoreResult.Unreadable(e))
        }
    }

    /**
     * Writes the confirmed [backup]. Any failure leaves the app as it was, since the store writes
     * in one transaction, so every one is the same answer to the person; its cause goes in the result.
     */
    fun apply(backup: Backup): RestoreResult = try {
        if (store.restore(backup)) RestoreResult.Restored(backup.servers.firstOrNull()?.address) else RestoreResult.NotEmpty
    } catch (e: Exception) {
        RestoreResult.Failed(e)
    }

    // Null past the ceiling: a file picked by mistake (a video) must not be read whole into memory.
    private fun readUpToCeiling(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            if (output.size() > MAX_BYTES) return null
        }
    }

    companion object {
        /** A backup of a large personal library is a few megabytes (choice 10). */
        const val MAX_BYTES = 50L * 1024 * 1024

        private const val BUFFER = 64 * 1024
    }
}
