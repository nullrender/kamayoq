package app.kamayoq.data

import app.kamayoq.core.backup.BackupFormat
import java.time.Instant
import java.time.ZoneId

/**
 * The folder backups go to. The app's side writes through Android's documents; the folder may
 * give another name than the one asked for, when that name is taken. [create] returns only the id
 * that finds the file again: once the file exists, every later step can fail and still delete it.
 */
interface BackupFolder {
    fun create(name: String): String

    /** The name the folder gave the file, or null when the folder does not say. */
    fun nameOf(id: String): String?
    fun write(id: String, text: String)
    fun read(id: String): String
    fun delete(id: String)
}

sealed interface BackupResult {
    /** [fileName] is null when the folder did not say it: the one asked for may not be the real one. */
    data class Written(val fileName: String?) : BackupResult

    /**
     * The file could not be written or read back. It was deleted, unless the folder refused that
     * too: then the refusal is in [cause].
     */
    data class WriteFailed(val cause: Exception) : BackupResult

    /** The file read back is not the backup written; it was deleted. */
    data object Mismatch : BackupResult
}

/**
 * Writes a backup of [store] to [folder] and reads it back: a backup only counts once the file
 * holds the same backup. One that does not is deleted, so it is never taken for a good one when
 * restoring (decision of 28/10/2026).
 */
class BackupExport(private val store: LibraryStore, private val folder: BackupFolder) {

    fun run(appVersion: String, now: Instant, zone: ZoneId): BackupResult {
        val backup = store.backup(appVersion, now)
        val requested = BackupFormat.fileName(now, zone)
        val id = try {
            folder.create(requested)
        } catch (e: Exception) {
            return BackupResult.WriteFailed(e)
        }
        val (name, keptText) = try {
            val name = folder.nameOf(id)
            folder.write(id, BackupFormat.write(backup))
            name to folder.read(id)
        } catch (e: Exception) {
            // A delete that also fails goes with the first error, which is the one that matters.
            runCatching { folder.delete(id) }.exceptionOrNull()?.let(e::addSuppressed)
            return BackupResult.WriteFailed(e)
        }
        val kept = try {
            BackupFormat.read(keptText)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (kept != backup) {
            folder.delete(id)
            return BackupResult.Mismatch
        }
        return BackupResult.Written(name)
    }
}
