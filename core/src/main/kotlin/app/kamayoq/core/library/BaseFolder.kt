package app.kamayoq.core.library

/**
 * The folders the app uses inside the base folder the person chose (decision of 27/10/2026): works
 * are read only from [LIBRARY].
 */
enum class BaseSubfolder(val folderName: String) {
    /** What the person brings from outside: downloaded by another app, copied by cable. */
    LIBRARY("Library"),

    /** The app's own copies of its data. `Downloads` joins when the app downloads anything. */
    BACKUPS("Backups"),
}

object BaseFolder {
    /**
     * The folder for [subfolder] among the base folder's [children], matched without regard to
     * case, as type folders are. Null when there is none: the app creates it.
     */
    fun find(children: List<FolderEntry>, subfolder: BaseSubfolder): FolderEntry? {
        val folders = children.filter { it.isFolder }
        // Two folders that differ only in case can live side by side on some providers.
        return folders.firstOrNull { it.name == subfolder.folderName }
            ?: folders.firstOrNull { it.name.equals(subfolder.folderName, ignoreCase = true) }
    }
}
