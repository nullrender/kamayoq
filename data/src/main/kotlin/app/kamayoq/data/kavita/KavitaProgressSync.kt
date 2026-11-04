package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.KavitaProgress
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.StoredResume
import app.kamayoq.data.SyncItem
import java.io.IOException
import java.time.Instant

/** A reader's turn on a chapter: only its own close ends it. */
data class KavitaOpened(val token: Long, val outcome: KavitaSyncOutcome)

sealed interface KavitaSyncOutcome {
    data object Settled : KavitaSyncOutcome

    /** The chapter took the server's position. */
    data object Adopted : KavitaSyncOutcome

    /** Both sides changed and disagree: nothing was sent, and only the person can choose. */
    data class Conflict(val remotePage: Int, val serverAt: String?, val pageCount: Int) : KavitaSyncOutcome
}

/** The position going back and forth, chapter by chapter (progress contract, section 2d). */
class KavitaProgressSync(
    private val client: KavitaClient,
    private val store: LibraryStore,
    private val now: () -> Instant = Instant::now,
    // For tests only: what a reader does between two chapters of a batch.
    private val beforeVisit: (SyncItem) -> Unit = {},
) {
    private var reading: String? = null
    private var readingToken = 0L

    // Each opening's turn, by chapter: a batch asks whether a chapter of a work opened since it began,
    // even if the reader closed it meanwhile.
    private var openings = 0L
    private val openedAt = mutableMapOf<String, Long>()

    /**
     * Throws [IOException] when the server is out of reach. One at a time, on what is stored now: a
     * second sync of the chapter from an older picture would take the first one's work for news.
     */
    @Synchronized
    fun chapter(address: String, key: String, known: SyncItem, moveResume: Boolean = true): KavitaSyncOutcome {
        val item = store.syncItem(known.chapterId) ?: return KavitaSyncOutcome.Settled
        val progress = store.progress(item.chapterId)
        val local = KavitaProgress.toRemote(progress.position, item.pageCount)
        val remote = client.progress(address, key, item.remoteId)
        // New content starts from zero here (rule E1), and the server's record is the old content's:
        // the first look only takes it as the base, however far it went.
        if (item.isEdition && item.agreedPage == null) {
            store.agree(item.chapterId, remote.page, remote.at, ownReread = false)
            return chapter(address, key, item)
        }
        // A send whose answer was lost and did arrive: without this, it would look like news from the server.
        val arrived = item.outboxState == SENT_UNCONFIRMED && item.outboxPage == remote.page
        if (arrived) store.agree(item.chapterId, remote.page, remote.at, ownReread = false)
        val agreed = if (arrived) remote.page else item.agreedPage
        when (KavitaProgress.decide(local, agreed, remote.page)) {
            KavitaProgress.Decision.IN_SYNC -> if (item.outboxState != null) store.clearQueue(item.chapterId)
            KavitaProgress.Decision.AGREE -> store.agree(item.chapterId, remote.page, remote.at, ownReread = false)
            KavitaProgress.Decision.SEND ->
                send(address, key, item, checkNotNull(local) { "Nothing to send" }, progress.readMark != null)
            KavitaProgress.Decision.ADOPT -> {
                val at = now()
                store.adoptRemote(item.chapterId, remote.page, item.pageCount, remote.at, at, LibraryStore.PREVIOUS, moveResume)
                return KavitaSyncOutcome.Adopted
            }
            KavitaProgress.Decision.CONFLICT -> return KavitaSyncOutcome.Conflict(remote.page, remote.at, item.pageCount)
        }
        return KavitaSyncOutcome.Settled
    }

    /** After the reader closes. With no server, the position waits in the queue. */
    fun afterReading(address: String, key: String, chapterId: String) {
        val item = store.syncItem(chapterId) ?: return
        try {
            ifClosed(address, key, item)
        } catch (_: IOException) {
            val page = KavitaProgress.toRemote(store.progress(chapterId).position, item.pageCount) ?: return
            store.queueProgress(chapterId, page, now())
        }
    }

    /**
     * The chapter about to open in the reader: left out of any batch until its own [closed]. Returns
     * the turn; a close of an older turn of the same chapter changes nothing.
     */
    @Synchronized
    fun register(chapterId: String): Long {
        val token = ++openings
        reading = chapterId
        readingToken = token
        openedAt[chapterId] = token
        return token
    }

    /** Syncs the chapter of turn [token] before it opens, under the lock any batch writes with. */
    @Synchronized
    fun check(address: String, key: String, chapterId: String, token: Long): KavitaSyncOutcome {
        if (token != readingToken) return KavitaSyncOutcome.Settled
        val item = store.syncItem(chapterId) ?: return KavitaSyncOutcome.Settled
        return chapter(address, key, item)
    }

    fun beforeOpening(address: String, key: String, chapterId: String): KavitaOpened {
        val token = register(chapterId)
        return KavitaOpened(token, check(address, key, chapterId, token))
    }

    @Synchronized
    fun closed(chapterId: String, token: Long) {
        if (token == readingToken && readingNow() == store.currentEdition(chapterId)) {
            reading = null
            readingToken = 0
        }
    }

    /**
     * What changed on either side since the last agreement, from [pagesRead] of a fresh import. A
     * conflict in a chapter stays as it is: the person sees it before opening it. A chapter the server
     * says it no longer has does not stop the others; any other failure does. The chapter in the
     * reader stays out: the reader holds its own copy of the progress, and closing it syncs that one.
     */
    fun all(address: String, key: String, pagesRead: Map<String, Int>) {
        val server = store.kavitaServer() ?: return
        val start = synchronized(this) { openings }
        store.syncItems(server.id).groupBy { it.seriesId }.values.forEach { work ->
            val changed = work.filter { it.outboxState != null || changedHere(it) || changedThere(it, pagesRead) }
            // Read before anything moves: an adopted chapter would look like a change made here, and a
            // sent one like no change at all.
            val movedHere = changed.any(::changedHere)
            val before = store.resumeOf(work.first().chapterId)
            val (fromServer, fromHere) = changed.partition { changedThere(it, pagesRead) }
            var adopted: SyncItem? = null
            // The server's news first, and settled even if a failure stops the rest: sent later, this
            // device's reading is still there to ask about.
            try {
                (fromServer + fromHere).forEach { item ->
                    beforeVisit(item)
                    if (visit(address, key, item) == KavitaSyncOutcome.Adopted) adopted = item
                }
            } finally {
                adopted?.let { settle(it, work, movedHere, before, start) }
            }
        }
    }

    // A chapter of the work open now, or opened since the batch began, is a reading here too.
    @Synchronized
    private fun settle(adopted: SyncItem, work: List<SyncItem>, movedHere: Boolean, before: StoredResume?, start: Long) {
        val open = readingNow()
        val readHere = work.any { it.chapterId == open || (openedAt[it.chapterId] ?: 0L) > start }
        store.settleResume(adopted.chapterId, movedHere || readHere, before)
    }

    // Opened again meanwhile: that reader holds its own copy, and its close sends it.
    @Synchronized
    private fun ifClosed(address: String, key: String, item: SyncItem) {
        if (item.chapterId != readingNow()) chapter(address, key, item)
    }

    // The check and the write under one lock: the reader cannot open this chapter in between.
    @Synchronized
    private fun visit(address: String, key: String, item: SyncItem): KavitaSyncOutcome? {
        if (item.chapterId == readingNow()) return null
        return try {
            chapter(address, key, item, moveResume = false)
        } catch (e: KavitaListingFailed) {
            if (e.code != HTTP_BAD_REQUEST && e.code != HTTP_NOT_FOUND) throw e
            null
        }
    }

    // The hash may have replaced the open chapter by a new edition since it opened.
    private fun readingNow(): String? = reading?.let(store::currentEdition)

    private fun changedHere(item: SyncItem): Boolean {
        val local = KavitaProgress.toRemote(store.progress(item.chapterId).position, item.pageCount)
        return local != null && local != (item.agreedPage ?: 0)
    }

    private fun changedThere(item: SyncItem, pagesRead: Map<String, Int>): Boolean {
        val base = item.agreedPage ?: 0
        return (pagesRead[item.remoteId.toString()] ?: base) != base
    }

    private fun send(address: String, key: String, item: SyncItem, page: Int, wasRead: Boolean) {
        store.markSending(item.chapterId, page, now())
        client.saveProgress(address, key, item, page)
        val after = client.progress(address, key, item.remoteId)
        // Anything else means another device wrote meanwhile: the queue keeps this, and the next sync sees it.
        if (after.page == page) {
            store.agree(item.chapterId, page, after.at, ownReread = wasRead && page < item.pageCount)
        }
    }

    private companion object {
        const val SENT_UNCONFIRMED = "SENT_UNCONFIRMED"
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
