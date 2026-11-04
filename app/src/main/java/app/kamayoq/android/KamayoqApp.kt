package app.kamayoq.android

import android.app.Application
import app.kamayoq.data.LibraryStore
import java.io.File
import kotlinx.coroutines.MainScope

/**
 * What the whole app shares, made once when the app starts and not for each screen: a screen
 * recreated (the phone turned) finds the same chapter opener, whose copies in use stay known, and
 * the same backup (developer.android.com, "Manual dependency injection", the container in the
 * Application class).
 */
open class KamayoqApp : Application() {
    lateinit var store: LibraryStore
        private set
    lateinit var folder: ChosenFolder
        private set
    lateinit var opening: ChapterOpening
        private set
    lateinit var backups: Backups
        private set
    lateinit var kavita: KavitaAccess
        private set
    lateinit var kavitaSync: KavitaSync
        private set

    // Set instead of everything above when the library cannot open: only the failure screen runs.
    var failure: LibraryFailure? = null
        private set

    internal open fun openLibrary(): LibraryOpening = LibraryDatabase.open(this)

    override fun onCreate() {
        super.onCreate()
        when (val library = openLibrary()) {
            is LibraryOpening.Failed -> failure = library.failure
            is LibraryOpening.Opened -> start(library.store)
        }
    }

    private fun start(library: LibraryStore) {
        store = library
        folder = ChosenFolder(this)
        kavita = KavitaAccess(this)
        kavitaSync = KavitaSync(store, kavita, File(cacheDir, "kavita"))
        opening = ChapterOpening(this, folder, store, kavitaSync::chapterFile)
        // Lives as long as the app: a backup started on a screen that goes away still ends.
        backups = Backups(MainScope())
    }
}
