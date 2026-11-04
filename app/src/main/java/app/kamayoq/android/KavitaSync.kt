package app.kamayoq.android

import android.util.Log
import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredChapter
import app.kamayoq.data.kavita.KavitaChapterFile
import app.kamayoq.data.kavita.KavitaChapterFiles
import app.kamayoq.data.kavita.KavitaClient
import app.kamayoq.data.kavita.KavitaImport
import app.kamayoq.data.kavita.KavitaProgressSync
import app.kamayoq.data.kavita.KavitaSyncOutcome
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
) : KavitaLibraryChoice {
    private val import = KavitaImport(client, store)
    private val files = KavitaChapterFiles(client, chapterFolder)
    private val progress = KavitaProgressSync(client, store)

    // Closings in the order they happen, apart from the screen that goes away.
    private val closing = Executors.newSingleThreadExecutor()

    // Address and key are one pair: a new connection waits for whatever is using the old one.
    private val connection = ReentrantReadWriteLock()

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
    fun keepHere(chapterId: String, conflict: KavitaSyncOutcome.Conflict) {
        store.keepLocal(chapterId, conflict.remotePage, conflict.pageCount, conflict.serverAt, Instant.now())
    }

    fun useKavita(chapterId: String, conflict: KavitaSyncOutcome.Conflict) {
        store.adoptRemote(chapterId, conflict.remotePage, conflict.pageCount, conflict.serverAt, Instant.now(), LibraryStore.CONFLICT)
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
