package app.kamayoq.android

import android.net.Uri
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.FolderWalk
import app.kamayoq.core.library.IgnoredFile
import app.kamayoq.data.LibraryStore

/** Reads the Library folder and brings the stored library in line with it. Runs off the main thread. */
object LibraryScan {
    /**
     * Returns the files the folder convention left out, for the person to see. Writes nothing when
     * [stillLatest] says a newer reading started before this one ended.
     */
    fun run(folder: ChosenFolder, tree: Uri, store: LibraryStore, stillLatest: () -> Boolean): List<IgnoredFile> {
        val files = folder.libraryId(tree)?.let { library -> FolderWalk.walk(library) { folder.children(tree, it) } }.orEmpty()
        val library = FolderConvention.read(files.map { it.path })
        store.importLocal(tree.toString(), library, files.associate { it.path to it.stamp }, stillLatest)
        return library.ignored
    }
}
