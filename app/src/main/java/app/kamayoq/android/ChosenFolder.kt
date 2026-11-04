package app.kamayoq.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.kamayoq.core.library.BaseFolder
import app.kamayoq.core.library.BaseSubfolder
import app.kamayoq.core.library.FolderAccess
import app.kamayoq.core.library.FolderEntry
import app.kamayoq.core.library.FolderPermission
import java.util.concurrent.atomic.AtomicInteger

/**
 * The base folder the person chose through the Storage Access Framework, and the permission Android
 * keeps for it across restarts. Read and write: the app writes only its own files there (folder
 * convention, rule 6), such as the subfolders it uses (decision of 27/10/2026). Open so the screen
 * tests can read a folder in memory.
 */
open class ChosenFolder(private val context: Context) {

    private val preferences = context.getSharedPreferences("chosen_folder", Context.MODE_PRIVATE)
    private val resolver: ContentResolver = context.contentResolver

    /**
     * Raised by each reading of the folder. Here, made once for the whole app, and not on a screen:
     * a recreated screen must not start counting again.
     */
    val readings = AtomicInteger()

    /** The folder chosen before, if any, even when its permission is gone. */
    open fun saved(): Uri? = preferences.getString(KEY_TREE, null)?.let(Uri::parse)

    /** Whether Android still lets the app read the saved folder. */
    open fun canRead(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /** Whether Android lets the app write in the saved folder; a read-only one is still read (rule E5). */
    open fun canWrite(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /**
     * Keeps the most the folder picker granted and remembers the folder. Throws [SecurityException]
     * when Android lets the app keep nothing of it; the folder chosen before then stays.
     */
    fun choose(tree: Uri) {
        FolderPermission.keep { access -> resolver.takePersistableUriPermission(tree, flagsOf(access)) }
        val previous = saved()
        if (previous != null && previous != tree) release(previous)
        preferences.edit().putString(KEY_TREE, tree.toString()).apply()
    }

    /** Gives back exactly what was kept of a folder no longer chosen. */
    private fun release(tree: Uri) {
        val kept = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return
        val access = if (kept.isWritePermission) FolderAccess.READ_WRITE else FolderAccess.READ
        resolver.releasePersistableUriPermission(tree, flagsOf(access))
    }

    private fun flagsOf(access: FolderAccess): Int = when (access) {
        FolderAccess.READ_WRITE -> Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        FolderAccess.READ -> Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** The base folder's name as the person sees it, such as `Kamayoq`. */
    fun name(tree: Uri): String {
        val base = DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree))
        return resolver.query(base, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("The folder provider gave no name for the chosen folder")
    }

    /**
     * The `Library` folder to read: created when the app may write; null when a read-only folder
     * has none, which is a library with nothing in it (rule E5).
     */
    fun libraryId(tree: Uri): String? =
        if (canWrite(tree)) subfolderId(tree, BaseSubfolder.LIBRARY)
        else BaseFolder.find(children(tree, rootId(tree)), BaseSubfolder.LIBRARY)?.id

    /** The base folder's [subfolder], created when it is missing. */
    open fun subfolderId(tree: Uri, subfolder: BaseSubfolder): String {
        val baseId = rootId(tree)
        BaseFolder.find(children(tree, baseId), subfolder)?.let { return it.id }
        val base = DocumentsContract.buildDocumentUriUsingTree(tree, baseId)
        val created = DocumentsContract.createDocument(resolver, base, Document.MIME_TYPE_DIR, subfolder.folderName)
            ?: error("The folder provider could not create ${subfolder.folderName}")
        return DocumentsContract.getDocumentId(created)
    }

    /** One query for all the children of a folder, with every column the scan needs. */
    open fun children(tree: Uri, folderId: String): List<FolderEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: error("The folder provider returned nothing for $folderId")
        return cursor.use {
            // A provider that is still loading would return part of the folder; better fail than
            // import a library with works missing.
            check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) { "The folder provider is still loading" }
            buildList {
                while (it.moveToNext()) {
                    add(
                        FolderEntry(
                            id = it.getString(0),
                            name = it.getString(1),
                            isFolder = it.getString(2) == Document.MIME_TYPE_DIR,
                            // The stamp is only compared with itself, so an unknown size stays
                            // the same unknown value from one scan to the next.
                            size = if (it.isNull(3)) UNKNOWN_SIZE else it.getLong(3),
                            modified = if (it.isNull(4)) null else it.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_TREE = "tree_uri"
        const val UNKNOWN_SIZE = -1L
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
