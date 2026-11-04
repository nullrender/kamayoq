package app.kamayoq.core.library

/** One child of a folder, as one listing query returns it. */
data class FolderEntry(
    /** The identifier the folder's provider gives it, used to list or open it later. */
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long?,
)

/** A file under the chosen folder: where it is, how to open it, and its stamp. */
data class FoundFile(val path: String, val id: String, val stamp: FileStamp)

/**
 * Lists every file under a chosen folder with one listing per folder, the practice the Android
 * documentation recommends over a query per file. The listing itself is given by the caller, so
 * this runs the same on the JVM and on the phone.
 */
object FolderWalk {
    /** Hidden folders (`.thumbnails` and the like) are never entered: nothing in them is read. */
    fun walk(rootId: String, children: (folderId: String) -> List<FolderEntry>): List<FoundFile> {
        val found = mutableListOf<FoundFile>()
        // Breadth first: each folder waits with its path until its single listing.
        val pending = ArrayDeque(listOf(rootId to ""))
        while (pending.isNotEmpty()) {
            val (folderId, folderPath) = pending.removeFirst()
            for (entry in children(folderId)) {
                val path = if (folderPath.isEmpty()) entry.name else "$folderPath/${entry.name}"
                when {
                    !entry.isFolder -> found += FoundFile(path, entry.id, FileStamp(entry.size, entry.modified))
                    !entry.name.startsWith('.') -> pending.addLast(entry.id to path)
                }
            }
        }
        return found
    }

    /**
     * Finds the file or folder at [path] under the chosen folder, listing only the folders on the
     * way. Null when it is no longer there.
     */
    fun find(rootId: String, path: String, children: (folderId: String) -> List<FolderEntry>): FolderEntry? {
        var folderId = rootId
        var entry: FolderEntry? = null
        for (name in path.split('/')) {
            // Only a folder can hold the next name on the path.
            if (entry != null && !entry.isFolder) return null
            entry = children(folderId).firstOrNull { it.name == name } ?: return null
            folderId = entry.id
        }
        return entry
    }
}
