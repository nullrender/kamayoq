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

    /** Works in `Books`, each a folder with one `Vol. 1.epub`; none unless a test adds them. */
    var books: List<String> = emptyList()

    /** Works in `Novels`, each a folder with one compiled file named after it; none unless a test adds them. */
    var novels: List<String> = emptyList()

    /** When the compiled files in `Novels` last changed, as the folder says. */
    var novelsModified = 1L

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
            "library" -> listOf(FolderEntry("comics", "Comics", isFolder = true, size = 0, modified = 1)) +
                listOfNotNull(FolderEntry("books", "Books", isFolder = true, size = 0, modified = 1).takeIf { books.isNotEmpty() }) +
                listOfNotNull(FolderEntry("novels", "Novels", isFolder = true, size = 0, modified = 1).takeIf { novels.isNotEmpty() })
            "books" -> books.map { FolderEntry("book:$it", it, isFolder = true, size = 0, modified = 1) }
            "novels" -> novels.map { FolderEntry("novel:$it", it, isFolder = true, size = 0, modified = 1) }
            in novels.map { "novel:$it" } -> folderId.removePrefix("novel:").let { title ->
                listOf(FolderEntry(novelFileId(title), "$title.epub", isFolder = false, size = 10, modified = novelsModified))
            }
            "comics" -> titles.map { FolderEntry(it, it, isFolder = true, size = 0, modified = 1) } +
                looseFiles.map { FolderEntry(it, it, isFolder = false, size = 10, modified = 1) }
            in books.map { "book:$it" } -> listOf(FolderEntry(bookFileId(folderId.removePrefix("book:")), "Vol. 1.epub", isFolder = false, size = 10, modified = 1))
            else -> listOf(FolderEntry("$folderId/cap", "Cap. 1.cbz", isFolder = false, size = 10, modified = 1))
        }
    }

    /** The document id of the book file of the work [title] in `Books`. */
    fun bookFileId(title: String) = "book:$title/vol1"

    /** The document id of the compiled file of the work [title] in `Novels`. */
    fun novelFileId(title: String) = "novel:$title/file"
}
