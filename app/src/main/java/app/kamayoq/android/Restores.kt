package app.kamayoq.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kamayoq.core.backup.Backup
import app.kamayoq.data.RestoreOpening
import app.kamayoq.data.RestoreResult
import kotlinx.coroutines.CoroutineScope

/**
 * Restoring a backup as the Library shows it: the file being read, the backup waiting for the
 * person's confirmation, the restore running and its result. It belongs to the app, like [Backups],
 * so a recreated screen still shows the confirmation and gets the result.
 */
class Restores(private val scope: CoroutineScope) {
    var reading by mutableStateOf(false)
        private set
    var restoring by mutableStateOf(false)
        private set

    /** Read and checked, waiting for the person's confirmation. */
    var offer by mutableStateOf<Backup?>(null)
        private set
    var result by mutableStateOf<RestoreResult?>(null)
        private set

    /** Runs [read] on the file picked. Main thread only, as [Backups.start]: two taps cannot both pass. */
    fun open(read: suspend () -> RestoreOpening) {
    }

    /** Nothing is written: the person may pick another file. */
    fun cancel() {
    }

    /** Writes the backup waiting for confirmation with [apply]. */
    fun confirm(apply: suspend (Backup) -> RestoreResult) {
    }
}
