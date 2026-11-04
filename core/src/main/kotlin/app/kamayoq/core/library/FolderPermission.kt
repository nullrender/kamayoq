package app.kamayoq.core.library

/** How much of the chosen folder Android is asked to keep for the app across restarts. */
enum class FolderAccess { READ_WRITE, READ }

object FolderPermission {
    /**
     * Keeps the most Android allows: reading and writing, else only reading, since a folder the app
     * can only read is still imported (rule E5). [take] asks Android and throws [SecurityException]
     * when it refuses. When even reading is refused, that exception goes to the caller, which tells
     * the person instead of closing the app.
     */
    fun keep(take: (FolderAccess) -> Unit) {
        try {
            take(FolderAccess.READ_WRITE)
        } catch (e: SecurityException) {
            take(FolderAccess.READ)
        }
    }
}
