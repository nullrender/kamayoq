package app.kamayoq.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kamayoq.data.BackupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The backup as the Library shows it: whether one is running, the last result and where it went.
 * It belongs to the app, not to one screen, so a recreated screen still shows the backup running
 * and gets its result.
 */
class Backups(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<BackupResult?>(null)
        private set

    // Where the last backup went, as the person sees the path: the base folder's own name first.
    var place by mutableStateOf("")
        private set

    /**
     * Runs [write], which gives the place (null when it could not be read) and the result. A second
     * call while one runs does nothing, as Mihon's manual backup (docs/research/one-backup-at-a-time.md).
     * Main thread only: [running] is read and set there, so two taps cannot both pass.
     */
    fun start(write: suspend () -> Pair<String?, BackupResult>) {
        if (running) return
        running = true
        result = null
        scope.launch {
            val (writtenTo, outcome) = write()
            writtenTo?.let { place = it }
            result = outcome
            running = false
        }
    }
}
