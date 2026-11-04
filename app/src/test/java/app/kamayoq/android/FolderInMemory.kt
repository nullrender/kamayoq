package app.kamayoq.android

import android.content.Context
import android.net.Uri
import app.kamayoq.core.library.BaseSubfolder
import app.kamayoq.core.library.FolderEntry

/** A chosen folder with [titles] as works in `Comics`, each with one chapter, for the screen tests. */
class FolderInMemory(context: Context, var titles: List<String>) : ChosenFolder(context) {
    val tree: Uri = Uri.parse("content://test/tree/primary%3AKamayoq")

    /** Files the folder convention leaves out, directly in `Comics`. */
    var looseFiles: List<String> = emptyList()

    /** The next readings fail, as a folder that stopped answering. */
    var failing = false

    /** A folder the app can only read, with no `Library` in it: creating one fails. */
    var readOnlyWithoutLibrary = false

    /** False when no folder was chosen yet. */
    var chosen = true

    override fun saved(): Uri? = tree.takeIf { chosen }

    override fun canRead(tree: Uri) = true

    override fun canWrite(tree: Uri) = !readOnlyWithoutLibrary

    override fun subfolderId(tree: Uri, subfolder: BaseSubfolder): String {
        check(!readOnlyWithoutLibrary) { "The folder provider could not create ${subfolder.folderName}" }
        return "library"
    }

    override fun children(tree: Uri, folderId: String): List<FolderEntry> {
        check(!failing) { "The folder stopped answering" }
        return when (folderId) {
            rootId(tree) -> emptyList()
            "library" -> listOf(FolderEntry("comics", "Comics", isFolder = true, size = 0, modified = 1))
            "comics" -> titles.map { FolderEntry(it, it, isFolder = true, size = 0, modified = 1) } +
                looseFiles.map { FolderEntry(it, it, isFolder = false, size = 10, modified = 1) }
            else -> listOf(FolderEntry("$folderId/cap", "Cap. 1.cbz", isFolder = false, size = 10, modified = 1))
        }
    }
}
