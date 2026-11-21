package app.kamayoq.android

import android.util.Log
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.KavitaPlace
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.WorkCovers
import app.kamayoq.data.kavita.KavitaChapterFile
import app.kamayoq.data.kavita.KavitaChapterFiles
import app.kamayoq.data.kavita.KavitaClient
import app.kamayoq.data.kavita.KavitaImport
import app.kamayoq.data.kavita.KavitaProgressSync
import app.kamayoq.data.kavita.KavitaSyncOutcome
import app.kamayoq.data.kavita.StoredKavitaKey
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Everything the app does with the Kavita server, on the connection the person made. */
class KavitaSync(
    private val store: LibraryStore,
    private val access: KavitaAccess,
    chapterFolder: File,
    private val client: KavitaClient = KavitaClient(),
    covers: WorkCovers? = null,
    downloadFolder: File? = null,
) : KavitaLibraryChoice {
    private val import = KavitaImport(client, store, covers)
    private val files = KavitaChapterFiles(client, chapterFolder, downloadFolder)

    // One download at a time, in the order asked, apart from the screens that asked.
    private val downloading = Executors.newSingleThreadExecutor()

    // The chapters asked for and not yet done, by id.
    private val queued = mutableSetOf<String>()
    private val progress = KavitaProgressSync(client, store)

    // Closings in the order they happen, apart from the screen that goes away.
    private val closing = Executors.newSingleThreadExecutor()

    // Address and key are one pair: a new connection waits for whatever is using the old one.
    private val connection = ReentrantReadWriteLock()

    /** How the downloads asked on each work's screen go, by work (decision 5: no queue screen). */
    val downloads = MutableStateFlow<Map<String, WorkDownloads>>(emptyMap())

    /** Raised each time the works or positions change, so open screens read them again. */
    val changes = MutableStateFlow(0)

    /**
     * A key not kept changes nothing: the stored address must keep matching the stored key, or the
     * old key would go to the new address.
     */
    /**
     * A key not kept changes nothing: the stored address must keep matching the stored key, or the
     * old key would go to the new address. Asking the server runs outside the lock; only keeping
     * the new pair waits for what uses the old one.
     */
    fun connect(address: String, key: String): KavitaConnection =
        access.connect(address, key, refusal = { refusal(address, key, it) }) { save ->
            connection.write {
                val (connection, kept) = save()
                // Only the attempt whose pair was kept links: an older one that lost keeps nothing.
                if (kept && connection is KavitaConnection.Connected) {
                    store.linkKavita(address, connection.version, connection.accountId)
                }
                connection
            }
        }

    // The account id alone proves little: it is 1 in every new Kavita (decision 6 of 02/11/2026). One
    // library with its old name is enough: a rename or a deletion must not lock the person out.
    private fun refusal(address: String, key: String, connection: KavitaConnection.Connected): KavitaConnection? {
        if (!store.acceptsKavitaAccount(connection.accountId)) return KavitaConnection.OtherAccount
        val remembered = access.rememberedLibraries()
        if (remembered.isEmpty()) return null
        return try {
            val names = client.libraries(address, key).associate { it.id to it.name }
            KavitaConnection.OtherAccount.takeIf { remembered.none { (id, name) -> names[id] == name } }
        } catch (_: IOException) {
            KavitaConnection.Unreachable
        }
    }

    /**
     * The address of a server stored with no key saved, as after a restore: the key never goes in
     * the backup (docs/decisions/backup-restore-2026-11-18.md, choice 12).
     */
    fun waitingForKey(): String? {
        val server = store.kavitaServer() ?: return null
        // Only a key never saved here: one that cannot be opened keeps its own message, as before.
        return server.address.takeIf { access.storedKey() == StoredKavitaKey.Absent }
    }

    /** Reads the marked libraries and the positions again; with nothing connected, there is nothing to read. */
    fun importNow(): Unit = connection.read {
        val server = store.kavitaServer() ?: return
        val key = key()
        try {
            val pagesRead = import.run(key)
            access.rememberLibraries(client.libraries(server.address, key).filter { it.id in server.marked })
            progress.all(server.address, key, pagesRead)
        } finally {
            // What was written before a failure counts too.
            changed()
        }
    }

    /** A reader's turn: [conflict] is what to ask before opening; [token] ends it in [finishReading]. */
    data class ReaderTurn(val token: Long, val conflict: KavitaSyncOutcome.Conflict?)

    /**
     * Just before a chapter opens (decision 7). No conflict when there is nothing to ask, also with
     * no network: then the chapter opens where it is here, and the next sync compares. The chapter
     * stays out of any batch until [finishReading] with the same turn.
     */
    fun beforeOpening(chapterId: String): ReaderTurn {
        val token = progress.register(chapterId)
        // Not behind a new connection that waits for a long import: then the chapter opens as with
        // no network.
        val lock = connection.readLock()
        if (!lock.tryLock()) return ReaderTurn(token, null)
        return try {
            ReaderTurn(token, progress.check(address(), key(), chapterId, token) as? KavitaSyncOutcome.Conflict)
        } catch (_: IOException) {
            ReaderTurn(token, null)
        } finally {
            lock.unlock()
        }
    }

    /** The reader left: ends its turn and sends the position, off the screen's own life. */
    fun finishReading(chapterId: String, token: Long, saves: OpeningTurns) {
        closing.execute {
            runBlocking { saves.waitForSaves() }
            progress.closed(chapterId, token)
            afterReading(chapterId)
        }
    }

    private fun afterReading(chapterId: String) {
        val current = store.currentEdition(chapterId)
        if (store.syncItem(current) == null) return
        connection.read {
            try {
                progress.afterReading(address(), key(), current)
            } catch (e: IOException) {
                Log.e("Kamayoq", "Sending the position to Kavita failed", e)
            }
        }
        changed()
    }

    // The position goes when the reader closes this chapter.
    /** [book] says the chapter is a book, whose page is a section with a marker. */
    fun keepHere(chapterId: String, conflict: KavitaSyncOutcome.Conflict, book: Boolean = false) {
        if (book) {
            store.keepLocalText(chapterId, KavitaPlace(conflict.remotePage, conflict.remoteMarker), conflict.serverAt)
        } else {
            store.keepLocal(chapterId, conflict.remotePage, conflict.pageCount, conflict.serverAt, Instant.now())
        }
    }

    fun useKavita(chapterId: String, conflict: KavitaSyncOutcome.Conflict, book: Boolean = false) {
        if (book) {
            store.adoptRemoteText(chapterId, KavitaPlace(conflict.remotePage, conflict.remoteMarker), conflict.pageCount, conflict.serverAt, Instant.now())
        } else {
            store.adoptRemote(chapterId, conflict.remotePage, conflict.pageCount, conflict.serverAt, Instant.now(), LibraryStore.CONFLICT)
        }
    }

    /** True when the person downloaded [chapter] to this device. */
    fun isDownloaded(chapter: StoredChapter): Boolean = files.isDownloaded(chapter)

    /** The bytes the downloaded copies of [chapters] take. */
    fun downloadedBytes(chapters: List<StoredChapter>): Long = files.downloadedBytes(chapters)

    /**
     * Downloads the chapters of [chapters] not on this device yet, one after another, off the
     * screen's own life; [downloads] says how it goes. Returns how many go in.
     */
    fun download(workId: String, chapters: List<StoredChapter>): Int {
        // A chapter already waiting or coming is not asked twice, so the count stays the work's.
        val wanted = synchronized(queued) {
            chapters.filter(::canDownload).filterNot { files.isDownloaded(it) || it.id in queued }
                .also { new -> queued += new.map { it.id } }
        }
        if (wanted.isEmpty()) return 0
        // A new request while one runs adds to it; otherwise the count starts again.
        downloads.update { all ->
            val now = all[workId]?.takeIf { it.running }
            all + (workId to (now?.copy(total = now.total + wanted.size) ?: WorkDownloads(0, wanted.size, 0)))
        }
        downloading.execute {
            for (chapter in wanted) {
                val came = try {
                    files.download(chapter, stillWanted = { true }) { connection.read { address() to key() } }
                    true
                } catch (e: Exception) {
                    // Any failure counts as one: an error that is not the network's would otherwise end
                    // this thread's loop and leave "Downloading" on the screen for good.
                    Log.e("Kamayoq", "Downloading a chapter failed", e)
                    false
                } finally {
                    synchronized(queued) { queued -= chapter.id }
                }
                downloads.update { all ->
                    val now = all[workId] ?: return@update all
                    all + (workId to if (came) now.copy(done = now.done + 1) else now.copy(failed = now.failed + 1))
                }
                changed()
            }
        }
        return wanted.size
    }

    /** A chapter the reader opens: a PDF or a CBR from Kavita would come down only to be refused. */
    fun canDownload(chapter: StoredChapter): Boolean =
        chapter.format == ChapterFormat.ARCHIVE || chapter.format == ChapterFormat.EPUB

    /** Deletes [chapters] from this device and returns the bytes freed; the progress stays. */
    fun deleteDownloads(chapters: List<StoredChapter>): Long {
        val freed = chapters.sumOf(files::delete)
        changed()
        return freed
    }

    // Asked only when a download is needed: what is kept can be read with no usable key.
    fun chapterFile(chapter: StoredChapter, stillWanted: () -> Boolean): KavitaChapterFile =
        files.file(chapter, stillWanted) { connection.read { address() to key() } }

    override suspend fun load(): List<RemoteLibrary> = withContext(Dispatchers.IO) {
        connection.read { client.libraries(address(), key()) }
    }

    override suspend fun marked(): Set<Int> = withContext(Dispatchers.IO) {
        store.kavitaServer()?.marked.orEmpty().toSet()
    }

    override suspend fun include(libraries: Set<Int>) = withContext(Dispatchers.IO) {
        store.markLibraries(checkNotNull(store.kavitaServer()) { "No Kavita server" }.id, libraries.toList())
        importNow()
    }

    private fun changed() {
        changes.update { it + 1 }
    }

    private fun address(): String = store.kavitaServer()?.address ?: throw IOException("No Kavita server")

    // Only the pair saved together: a key made for another address never leaves here.
    private fun key(): String {
        val (address, key) = access.storedPair() ?: throw IOException("No Kavita key")
        if (address != store.kavitaServer()?.address) throw IOException("The saved key is for another address")
        return key
    }
}

/** The downloads asked on one work: [done] and [failed] of [total]; running until they add up. */
data class WorkDownloads(val done: Int, val total: Int, val failed: Int) {
    val running get() = done + failed < total
}
