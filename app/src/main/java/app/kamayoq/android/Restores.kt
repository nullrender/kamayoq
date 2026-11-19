package app.kamayoq.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kamayoq.core.backup.Backup
import app.kamayoq.data.RestoreOpening
import app.kamayoq.data.RestoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    // Whether takeResult already gave the current result; each new result is untaken.
    private var taken = false

    private val busy get() = reading || restoring

    /** Runs [read] on the file picked. Main thread only, as [Backups.start]: two taps cannot both pass. */
    fun open(read: suspend () -> RestoreOpening) {
        if (busy) return
        reading = true
        offer = null
        result = null
        scope.launch {
            when (val opening = read()) {
                is RestoreOpening.Ready -> offer = opening.backup
                is RestoreOpening.Refused -> {
                    taken = false
                    result = opening.result
                }
            }
            reading = false
        }
    }

    /**
     * The result, once: what follows a restore (reading the folder and Kavita again) runs for it
     * once, not again on every screen made after it. [result] keeps it for the message.
     */
    fun takeResult(): RestoreResult? {
        if (taken) return null
        taken = true
        return result
    }

    /** Nothing is written: the person may pick another file. */
    fun cancel() {
        offer = null
    }

    /** Writes the backup waiting for confirmation with [apply]. */
    fun confirm(apply: suspend (Backup) -> RestoreResult) {
        val backup = offer ?: return
        if (busy) return
        offer = null
        restoring = true
        scope.launch {
            taken = false
            result = apply(backup)
            restoring = false
        }
    }
}
