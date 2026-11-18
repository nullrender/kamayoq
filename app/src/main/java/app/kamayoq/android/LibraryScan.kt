package app.kamayoq.android

import android.net.Uri
import android.util.Log
import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FolderConvention
import app.kamayoq.core.library.FolderWalk
import app.kamayoq.core.library.IgnoredFile
import app.kamayoq.core.library.SplitFiles
import app.kamayoq.core.reader.ContentsChapter
import app.kamayoq.data.LibraryStore
import java.io.IOException

/** Reads the Library folder and brings the stored library in line with it. Runs off the main thread. */
object LibraryScan {
    /**
     * Returns the files the folder convention left out, for the person to see. Writes nothing when
     * [stillLatest] says a newer reading started before this one ended. [contents] reads the table of
     * contents of the file with a document id: a compiled novel is listed by chapter.
     */
    fun run(
        folder: ChosenFolder,
        tree: Uri,
        store: LibraryStore,
        stillLatest: () -> Boolean,
        contents: (documentId: String) -> List<ContentsChapter>,
    ): List<IgnoredFile> {
        val files = folder.libraryId(tree)?.let { library -> FolderWalk.walk(library) { folder.children(tree, it) } }.orEmpty()
        val library = FolderConvention.read(files.map { it.path })
        val stamps = files.associate { it.path to it.stamp }
        val ids = files.associate { it.path to it.id }
        // Opening a file costs a copy of it: only a file that is new, or changed since its contents
        // were read, is opened. The others keep the chapters stored for them.
        val known = store.splitFiles(tree.toString())
        val expanded = SplitFiles.expand(library) { file ->
            val stored = known[file.path]
            if (stored != null && stored.stamp == ContentCheck.stampOf(file, stamps)) {
                stored.chapters
            } else {
                try {
                    contents(ids.getValue(file.path))
                } catch (e: IOException) {
                    // One file that cannot be read must not take the whole Library away: it keeps
                    // the chapters it had, or stays whole, and opening it says what is wrong.
                    Log.e("Kamayoq", "Reading the contents of a file failed", e)
                    stored?.chapters.orEmpty()
                }
            }
        }
        store.importLocal(tree.toString(), expanded, stamps, stillLatest)
        return library.ignored
    }
}
