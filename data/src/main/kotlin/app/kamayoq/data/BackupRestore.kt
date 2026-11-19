package app.kamayoq.data

import app.kamayoq.core.backup.Backup
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

    fun open(input: () -> InputStream): RestoreOpening = RestoreOpening.Refused(RestoreResult.NotEmpty)

    fun apply(backup: Backup): RestoreResult = RestoreResult.NotEmpty

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
