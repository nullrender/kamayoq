package app.kamayoq.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.kamayoq.core.backup.Backup
import app.kamayoq.core.backup.BackupAgreement
import app.kamayoq.core.backup.BackupAlternative
import app.kamayoq.core.backup.BackupChapter
import app.kamayoq.core.backup.BackupOutbox
import app.kamayoq.core.backup.BackupServer
import app.kamayoq.core.backup.BackupWork
import app.kamayoq.core.kavita.ImportedChapter
import app.kamayoq.core.kavita.ImportedWork
import app.kamayoq.core.kavita.KavitaProgress
import app.kamayoq.core.kavita.KavitaRelink
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.Work
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.data.db.CurrentOfVersion
import app.kamayoq.data.db.KamayoqDatabase
import app.kamayoq.data.db.RemoteOfVersion
import app.kamayoq.data.db.RemoteVersions
import app.kamayoq.data.db.VersionsOfSource
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class StoredWork(
    val id: String,
    val title: String,
    val alternativeTitle: String?,
    val type: WorkType,
    val subtype: ComicsSubtype?,
    /** Where it comes from: 'local' or [LibraryStore.KAVITA]. */
    val origin: String,
)

data class StoredChapter(
    val id: String,
    val workId: String,
    /** Path inside the chosen folder, as the folder convention gives it. */
    val path: String,
    val name: String,
    val kind: UnitKind,
    val number: Double?,
    val format: ChapterFormat,
    /** False when the file is gone from the folder; it is kept with its progress (rule E1). */
    val present: Boolean,
    /**
     * True until the content hash confirms this is the same content the progress belongs to: on
     * the first opening, and after the file changed or came back. See [LibraryStore.confirmContent].
     */
    val needsContentCheck: Boolean,
    /** Where it comes from: 'local' or [LibraryStore.KAVITA]. */
    val origin: String,
)

/**
 * Finds what is stored for each path of a reading. The exact path first; else a stored path that
 * differs only in letter case, which is the same folder renamed, unless the reading has that path
 * too: then the two live side by side (decisions of 30/10/2026). Each stored row serves one path.
 */
private class PathMatch<T : Any>(stored: List<T>, incoming: List<String>, pathOf: (T) -> String) {
    private val exact = stored.associateBy(pathOf)
    private val renamed: MutableMap<String, MutableList<T>> =
        stored.filter { pathOf(it) !in incoming }.groupByTo(mutableMapOf()) { fold(pathOf(it)) }

    fun of(path: String): T? = exact[path] ?: renamed[fold(path)]?.removeFirstOrNull()

    private fun fold(path: String) = path.lowercase(Locale.ROOT)
}

/** What sending a chapter's position to Kavita needs, and what was last agreed about it. */
data class SyncItem(
    val chapterId: String,
    val remoteId: Int,
    val volumeId: Int,
    val seriesId: Int,
    val libraryId: Int,
    val pageCount: Int,
    val agreedPage: Int?,
    val serverAt: String?,
    val outboxState: String?,
    val outboxPage: Int?,
    /** Took the place of another edition: whatever the server has belongs to that content. */
    val isEdition: Boolean,
)

/** A position kept aside: the one not chosen in a conflict, or the one before the server's news. */
data class StoredAlternative(
    val reason: String,
    val chapterId: String,
    val chapterName: String,
    val page: Int,
    val total: Int,
    val at: Instant,
    val device: String,
)

/** Where a work reopens and the position there. */
data class StoredResume(val chapterId: String, val position: StripPosition?)

/** The Kavita server of this app (one address only, by design), with the [marked] libraries. */
data class StoredServer(val id: String, val address: String, val marked: List<Int>)

/** What opening a chapter reads at one moment: [current] is the edition to read, in [chapters]. */
data class ChapterOpeningList(val current: String, val chapters: List<StoredChapter>)

/**
 * What the app keeps about the works in the folder the person chose and the reading of each
 * chapter. The driver must have foreign keys on: the tables refer to each other.
 */
class LibraryStore(driver: SqlDriver, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private val database = KamayoqDatabase(driver)

    fun syncItems(server: String): List<SyncItem> =
        database.chapterQueries.syncItems(server, ::syncItem).executeAsList()

    /** Null for a chapter whose position does not go to Kavita. */
    fun syncItem(chapterId: String): SyncItem? =
        database.chapterQueries.syncItem(chapterId, ::syncItem).executeAsOneOrNull()

    /** The server has this page too: the chapter leaves the queue. [ownReread] is rule C1. */
    fun agree(chapterId: String, page: Int, serverAt: String?, ownReread: Boolean) = database.transaction {
        database.progressAgreementQueries.agree(chapterId, page.toLong(), serverAt, if (ownReread) 1L else 0L)
        database.progressOutboxQueries.clear(chapterId)
    }

    /** Both sides are where they were: whatever was on its way no longer matters. */
    fun clearQueue(chapterId: String) {
        database.progressOutboxQueries.clear(chapterId)
    }

    /** Waiting for the network. What was sent and not confirmed stays as it was: rereading settles it. */
    fun queueProgress(chapterId: String, page: Int, at: Instant) = database.transaction {
        val queued = database.progressOutboxQueries.ofChapter(chapterId).executeAsOneOrNull()
        if (queued?.state != SENT_UNCONFIRMED) {
            database.progressOutboxQueries.queue(chapterId, page.toLong(), TO_SEND, at.toEpochMilli())
        }
    }

    /** Written before the request: a process that dies after it must reread, not send again. */
    fun markSending(chapterId: String, page: Int, at: Instant) {
        database.progressOutboxQueries.queue(chapterId, page.toLong(), SENT_UNCONFIRMED, at.toEpochMilli())
    }

    /**
     * The server's position becomes this chapter's, and the one it replaces stays aside for [reason].
     * Only a position reaching the end marks; none unmarks (C1). Complete reads count on this device only.
     */
    fun adoptRemote(
        chapterId: String,
        page: Int,
        pageCount: Int,
        serverAt: String?,
        at: Instant,
        reason: String,
        moveResume: Boolean = true,
    ) {
        database.transaction {
            val before = progress(chapterId)
            val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
            // The first image is where any opening starts: nothing to go back to.
            before.position?.takeIf { it.page > 0 }?.let { keepAside(versionId, reason, chapterId, it) }
            val position = KavitaProgress.fromRemote(page, pageCount, at)
            val mark = before.readMark ?: ReadMark(at, ReadMarkSource.KAVITA).takeIf { KavitaProgress.isRead(page, pageCount) }
            writeProgress(chapterId, before.copy(readMark = mark, position = position))
            if (moveResume) database.workResumeQueries.save(versionId, chapterId)
            agree(chapterId, page, serverAt, ownReread = false)
        }
    }

    /** Where the work of [chapterId] reopens now. */
    fun resumeOf(chapterId: String): StoredResume? {
        val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
        return database.workResumeQueries.resumeOfVersion(versionId).executeAsOneOrNull()
            ?.let { StoredResume(it, progress(it).position) }
    }

    /**
     * After a sync adopted [chapterId], the furthest one of its work. With a reading here to protect
     * ([localMoved]) the work keeps reopening where it did, and that chapter is the position offered
     * instead (contract, case 5). Otherwise it reopens there, and [before], taken ahead of the
     * adoptions, stays aside: by now that chapter may hold the server's position too.
     */
    fun settleResume(chapterId: String, localMoved: Boolean, before: StoredResume?) = database.transaction {
        val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
        // Reopening where it already did is no news, unless a reading here elsewhere makes it a choice.
        if (!localMoved && before?.chapterId == chapterId) return@transaction
        if (localMoved) {
            progress(chapterId).position?.let { keepAside(versionId, CONFLICT, chapterId, it) }
        } else {
            before?.position?.takeIf { it.page > 0 }?.let { keepAside(versionId, PREVIOUS, before.chapterId, it) }
            database.workResumeQueries.save(versionId, chapterId)
        }
    }

    /** The copy read now may not be the newest: the next opening with a server must compare again. */
    fun doubtContent(chapterId: String) {
        database.chapterQueries.doubt(chapterId)
    }

    /** A conflict settled for this device: the server's position stays aside, and sending is free. */
    fun keepLocal(chapterId: String, page: Int, pageCount: Int, serverAt: String?, at: Instant) = database.transaction {
        val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
        keepAside(versionId, CONFLICT, chapterId, KavitaProgress.fromRemote(page, pageCount, at))
        agree(chapterId, page, serverAt, ownReread = false)
    }

    private fun keepAside(versionId: String, reason: String, chapterId: String, position: StripPosition) {
        database.alternativePositionQueries.keep(
            versionId, reason, chapterId, position.page.toLong(), position.totalPages.toLong(),
            position.at.toEpochMilli(), position.device,
        )
    }

    fun alternatives(workId: String): List<StoredAlternative> =
        database.alternativePositionQueries.ofWork(workId).executeAsList().map {
            StoredAlternative(it.reason, it.chapter_id, it.name, it.page.toInt(), it.total.toInt(), Instant.ofEpochMilli(it.at), it.device)
        }

    /** The position kept aside becomes the chapter's again; sending it follows the usual way. */
    fun useAlternative(workId: String, alternative: StoredAlternative) = database.transaction {
        val position = StripPosition(alternative.page, alternative.total, alternative.at, alternative.device)
        val current = progress(alternative.chapterId)
        // As adopting would: the end of a position from the server is a read chapter.
        val fromKavita = alternative.device == KavitaProgress.DEVICE && position.isAtEnd
        val mark = current.readMark ?: ReadMark(alternative.at, ReadMarkSource.KAVITA).takeIf { fromKavita }
        saveProgress(alternative.chapterId, current.copy(readMark = mark, position = position))
        forgetAlternative(alternative)
    }

    fun forgetAlternative(alternative: StoredAlternative) {
        val versionId = database.chapterQueries.versionOfChapter(alternative.chapterId).executeAsOne()
        database.alternativePositionQueries.forget(versionId, alternative.reason)
    }

    /**
     * Brings the stored works in line with a fresh read of [source], with the [stamps] of its
     * files. A work or chapter at the same path keeps its identifier and its progress; one that is
     * gone stays stored, out of the lists (rule E1: never delete in doubt), and comes back with its
     * progress if the path returns. A chapter whose file changed or came back is only kept as the
     * same once [confirmContent] sees the same content. [stillChosen] is asked inside the
     * transaction: a reading of a folder that stopped being the chosen one writes nothing, so a
     * folder forgotten meanwhile does not come back.
     */
    fun importLocal(
        source: String,
        library: LocalLibrary,
        stamps: Map<String, FileStamp>,
        stillChosen: () -> Boolean = { true },
    ) {
        database.transaction {
            if (!stillChosen()) return@transaction
            val findVersion = PathMatch(
                database.workVersionQueries.versionsOfSource(LOCAL, source).executeAsList(),
                library.works.map { it.path },
            ) { it.path }
            val versionIds = library.works.mapIndexed { index, work ->
                val version = findVersion.of(work.path)
                val (versionId, workCameBack) = storeWork(source, work, index.toLong(), version)
                // A work renamed in letter case moves all its chapters: they are compared by their
                // place inside it, or a new file could take an old one's identity. A chapter gone
                // meanwhile keeps the older letter case, of the same length.
                val findChapter = PathMatch(
                    database.chapterQueries.currentOfVersion(versionId).executeAsList(),
                    work.units.map { it.path },
                ) { work.path + it.path.drop(work.path.length) }
                val chapterIds = work.units.mapIndexed { unitIndex, unit ->
                    val stamp = ContentCheck.stampOf(unit, stamps)
                    storeChapter(versionId, unit, unitIndex.toLong(), stamp, workCameBack, findChapter.of(unit.path))
                }
                database.chapterQueries.markAbsentExcept(versionId, chapterIds)
                versionId
            }
            database.workVersionQueries.markAbsentExcept(LOCAL, source, versionIds)
        }
    }

    /**
     * Brings the stored works of [server] in line with [works], everything its marked libraries hold
     * now. Whatever is gone stays stored, out of the lists (rule E1). What comes back with another id
     * takes the place of what vanished, by rule 8b, so the reading stays with the app's own identifier;
     * the server has no record of it, so the agreement starts over. A reading made for other libraries
     * than [markedAtStart] writes nothing: the slow network must not bring back what was unmarked.
     */
    fun importKavita(server: String, works: List<ImportedWork>, markedAtStart: List<Int>? = null) {
        database.transaction {
            if (markedAtStart != null && kavitaServer()?.marked != markedAtStart) return@transaction
            val stored = database.workVersionQueries.remoteVersions(KAVITA, server).executeAsList()
            val byPath = stored.associateBy { it.path }
            val byId = stored.associateBy { it.id }
            val incoming = works.map { it.path }.toSet()
            val returned = KavitaRelink.works(
                stored.filter { it.path !in incoming }.map { KavitaRelink.GoneWork(it.id, it.remote_library_id?.toInt(), it.title) },
                works.filter { it.path !in byPath }.map { KavitaRelink.ArrivedWork(it.path, it.libraryId, it.title) },
            )
            val versionIds = works.mapIndexed { index, work ->
                val existing = byPath[work.path] ?: returned[work.path]?.let(byId::getValue)
                val (versionId, cameBack) = storeRemoteWork(server, work, index.toLong(), existing)
                storeRemoteChapters(versionId, work, cameBack)
                versionId
            }
            database.workVersionQueries.markAbsentExcept(KAVITA, server, versionIds)
        }
    }

    fun kavitaServer(): StoredServer? = database.transactionWithResult {
        database.kavitaServerQueries.all().executeAsList().firstOrNull()?.let { server ->
            StoredServer(
                server.id,
                server.address,
                database.kavitaLibraryQueries.ofServer(server.id).executeAsList().map { it.toInt() },
            )
        }
    }

    /** False for another account than the stored one: its reading is another one (decision 6). */
    fun acceptsKavitaAccount(accountId: Int?): Boolean {
        val stored = database.kavitaServerQueries.all().executeAsList().firstOrNull()?.account_id
        return stored == null || accountId == null || stored == accountId.toLong()
    }

    /** Keeps the server just connected to. The id stays when the address changes, so its works stay linked. */
    fun linkKavita(address: String, version: String, accountId: Int?) = database.transaction {
        val stored = database.kavitaServerQueries.all().executeAsList().firstOrNull()
        val account = accountId?.toLong()
        if (stored == null) {
            database.kavitaServerQueries.insert(newId(), address, version, account)
        } else {
            database.kavitaServerQueries.updateConnection(address, version, account ?: stored.account_id, stored.id)
        }
    }

    fun markLibraries(server: String, libraries: List<Int>) = database.transaction {
        database.kavitaLibraryQueries.unmarkAll(server)
        libraries.forEach { database.kavitaLibraryQueries.mark(server, it.toLong()) }
        keepOnlyLibraries(server, libraries)
    }

    /** Leaves in the lists only the works of [libraries]: unmarking must not wait for the network. */
    fun keepOnlyLibraries(server: String, libraries: List<Int>) {
        database.workVersionQueries.markLibrariesAbsentExcept(KAVITA, server, libraries.map(Int::toLong))
    }

    /** Returns the version and whether it is coming back after being gone. */
    private fun storeRemoteWork(server: String, work: ImportedWork, sortIndex: Long, existing: RemoteVersions?): Pair<String, Boolean> {
        val type = work.type.name
        val subtype = work.subtype?.name
        val library = work.libraryId.toLong()
        if (existing != null) {
            if (existing.path != work.path) database.workVersionQueries.movePath(work.path, existing.id)
            database.workQueries.update(work.title, null, type, subtype, existing.work_id)
            database.workVersionQueries.markRemotePresent(sortIndex, library, existing.id)
            return existing.id to (existing.present == 0L || existing.path != work.path)
        }
        val workId = newId()
        val versionId = newId()
        database.workQueries.insert(workId, work.title, null, type, subtype)
        database.workVersionQueries.insertRemote(versionId, workId, KAVITA, server, work.path, sortIndex, library)
        return versionId to false
    }

    private fun storeRemoteChapters(versionId: String, work: ImportedWork, workCameBack: Boolean) {
        val stored = database.chapterQueries.remoteOfVersion(versionId).executeAsList()
        val byPath = stored.associateBy { it.path }
        val byId = stored.associateBy { it.id }
        val incoming = work.chapters.map { it.path }.toSet()
        val returned = KavitaRelink.chapters(
            stored.filter { it.path !in incoming }.mapNotNull { row ->
                row.page_count?.let { KavitaRelink.Gone(row.id, row.number, it.toInt(), row.remote_file) }
            },
            work.chapters.filter { it.path !in byPath }.map { KavitaRelink.Arrived(it.path, it.number, it.pageCount, it.remoteFile) },
        )
        val chapterIds = work.chapters.mapIndexed { index, chapter ->
            val relinked = returned[chapter.path]?.let(byId::getValue)
            val existing = byPath[chapter.path] ?: relinked
            if (relinked != null) {
                database.chapterQueries.movePath(chapter.path, relinked.id)
                // The new id starts with no record in the server (KavitaFactsTest): that is the base now.
                database.progressAgreementQueries.agree(relinked.id, 0L, null, 0L)
            }
            storeRemoteChapter(versionId, chapter, index.toLong(), existing, workCameBack || relinked != null)
        }
        database.chapterQueries.markAbsentExcept(versionId, chapterIds)
    }

    private fun storeRemoteChapter(
        versionId: String,
        chapter: ImportedChapter,
        sortIndex: Long,
        existing: RemoteOfVersion?,
        cameBack: Boolean,
    ): String {
        val kind = chapter.kind.name
        val format = chapter.format.name
        val pages = chapter.pageCount.toLong()
        val volume = chapter.remoteVolumeId.toLong()
        if (existing == null) {
            val id = newId()
            database.chapterQueries.insertRemote(
                id, versionId, chapter.path, chapter.name, kind, chapter.number, format, sortIndex, chapter.stamp,
                pages, chapter.remoteFile, volume,
            )
            return id
        }
        val protectsSomething = existing.content_hash != null || hasProgress(existing.id)
        val changed = existing.stamp != chapter.stamp || cameBack || existing.present == 0L
        val needsCheck = existing.needs_check == 1L || (changed && protectsSomething)
        database.chapterQueries.updateRemote(
            chapter.name, kind, chapter.number, format, sortIndex, chapter.stamp, if (needsCheck) 1L else 0L,
            pages, chapter.remoteFile, volume, existing.id,
        )
        return existing.id
    }

    /**
     * Leaves in the lists only the works of [source], the folder chosen now. The others stay stored
     * with their progress (rule E1).
     */
    fun keepOnlyFolder(source: String, stillChosen: () -> Boolean = { true }) = database.transaction {
        if (stillChosen()) database.workVersionQueries.markOtherSourcesAbsent(LOCAL, source)
    }

    /** Returns the version and whether it is coming back after its folder was gone. */
    private fun storeWork(source: String, work: Work, sortIndex: Long, existing: VersionsOfSource?): Pair<String, Boolean> {
        val type = work.type.name
        val subtype = work.subtype?.name
        if (existing != null) {
            if (existing.path != work.path) database.workVersionQueries.movePath(work.path, existing.id)
            database.workQueries.update(work.title, work.alternativeTitle, type, subtype, existing.work_id)
            database.workVersionQueries.markPresent(sortIndex, existing.id)
            return existing.id to (existing.present == 0L)
        }
        val workId = newId()
        val versionId = newId()
        database.workQueries.insert(workId, work.title, work.alternativeTitle, type, subtype)
        database.workVersionQueries.insert(versionId, workId, LOCAL, source, work.path, sortIndex)
        return versionId to false
    }

    private fun storeChapter(
        versionId: String,
        unit: ReadingUnit,
        sortIndex: Long,
        stamp: String,
        workCameBack: Boolean,
        existing: CurrentOfVersion?,
    ): String {
        val kind = unit.kind.name
        val format = formatOf(unit.content).name
        if (existing == null) {
            val id = newId()
            database.chapterQueries.insert(
                id, versionId, unit.path, unit.name, kind, unit.number, format, sortIndex, stamp,
            )
            return id
        }
        if (existing.path != unit.path) database.chapterQueries.movePath(unit.path, existing.id)
        val cameBack = workCameBack || existing.present == 0L
        // Only what was read or hashed needs protecting; a chapter never opened just takes the new file.
        val protectsSomething = existing.content_hash != null || hasProgress(existing.id)
        val needsCheck = existing.needs_check == 1L || ((existing.stamp != stamp || cameBack) && protectsSomething)
        database.chapterQueries.update(
            unit.name, kind, unit.number, format, sortIndex, stamp, if (needsCheck) 1L else 0L, existing.id,
        )
        return existing.id
    }

    private fun hasProgress(chapterId: String): Boolean =
        database.chapterQueries.hasProgress(chapterId).executeAsOne() > 0

    /** The works present in the folder, in the order of the last import. */
    fun works(): List<StoredWork> =
        database.workVersionQueries.presentWorks { id, title, alternativeTitle, type, subtype, origin ->
            StoredWork(id, title, alternativeTitle, WorkType.valueOf(type), subtype?.let(ComicsSubtype::valueOf), origin)
        }.executeAsList()

    /** The chapters present in the folder, in reading order (rule E3). */
    fun chapters(workId: String): List<StoredChapter> =
        database.chapterQueries.presentOfWork(workId, ::storedChapter).executeAsList()

    fun progress(chapterId: String): ChapterProgress =
        database.chapterProgressQueries.progressOf(chapterId) { readAt, readSource, page, total, at, device, reads ->
            ChapterProgress(
                readMark = readAt?.let {
                    ReadMark(Instant.ofEpochMilli(it), ReadMarkSource.valueOf(checkNotNull(readSource)))
                },
                position = page?.let {
                    StripPosition(
                        page = it.toInt(),
                        totalPages = checkNotNull(total).toInt(),
                        at = Instant.ofEpochMilli(checkNotNull(at)),
                        device = checkNotNull(device),
                    )
                },
                completeReads = reads.toInt(),
            )
        }.executeAsOneOrNull() ?: ChapterProgress.NEVER_OPENED

    /** Saves the reading of a chapter; that chapter becomes where its work reopens. */
    fun saveProgress(chapterId: String, progress: ChapterProgress) {
        database.transaction {
            writeProgress(chapterId, progress)
            val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
            database.workResumeQueries.save(versionId, chapterId)
        }
    }

    private fun writeProgress(chapterId: String, progress: ChapterProgress) {
        val position = progress.position
        database.chapterProgressQueries.save(
            chapterId,
            progress.readMark?.at?.toEpochMilli(),
            progress.readMark?.source?.name,
            position?.page?.toLong(),
            position?.totalPages?.toLong(),
            position?.at?.toEpochMilli(),
            position?.device,
            progress.completeReads.toLong(),
        )
    }

    /**
     * Gives the hash of a chapter's whole content, computed when it opens, and returns the chapter
     * to read. Same content: the same chapter, with its progress. Other content: the old chapter
     * becomes a previous edition, kept with its progress out of the lists, and a new chapter at the
     * same place starts from zero (rule E1).
     */
    fun confirmContent(chapterId: String, hash: String): String = database.transactionWithResult {
        // Two openings may start from the same identifier; the first one may already have made a new
        // edition, which is the one to compare with.
        val current = currentEdition(chapterId)
        val chapter = database.chapterQueries.byId(current).executeAsOne()
        val sameContent = chapter.content_hash == hash
        // Never hashed and never read: nothing to tell apart, so this content is its first one. A
        // Kavita chapter may have a position from the server before it first opens here.
        val fromKavita = chapter.page_count != null
        val firstContent = chapter.content_hash == null && (fromKavita || !hasProgress(current))
        if (sameContent || firstContent) {
            database.chapterQueries.confirm(hash, current)
            current
        } else {
            val edition = newId()
            database.chapterQueries.replace(edition, current)
            database.chapterQueries.insertEdition(edition, chapter.present, hash, current)
            // The old edition's unsent position must not go to the server as this content's.
            database.progressOutboxQueries.clear(current)
            edition
        }
    }

    /**
     * The chapter that stands for [chapterId] now: itself, or the edition that took its place when
     * its content changed. A screen recreated with an old identifier finds the chapter again.
     */
    fun currentEdition(chapterId: String): String {
        var id = chapterId
        while (true) {
            // An unknown identifier stays as it is: the screen then says the chapter is missing.
            id = database.chapterQueries.byId(id).executeAsOneOrNull()?.replaced_by ?: return id
        }
    }

    /**
     * Returns the chapter to read for an image folder as it opens. [stampNow] is the folder's stamp
     * at this moment, null when it has no pages; [hash] reads the whole content, only when needed.
     */
    fun confirmFolder(
        chapterId: String,
        stampNow: String?,
        hash: () -> String,
        hashBeforeNames: () -> String,
        whileLatest: (() -> String) -> String = { it() },
    ): String {
        val current = currentEdition(chapterId)
        // No pages: nothing to compare, so an emptied folder never becomes a new edition.
        if (stampNow == null) return current
        val chapter = database.chapterQueries.byId(current).executeAsOne()
        // The stamp is compared here too: the list may come from before the folder was read again.
        // Only a change, or a content never hashed, costs reading every page.
        val mayHaveChanged = chapter.needs_check == 1L || chapter.content_hash == null || chapter.stamp != stampNow
        if (!mayHaveChanged) return current
        val now = hash()
        // A summary from before 30/10/2026 counted the whole path; the same pages stay the same chapter.
        val sameAsBefore = chapter.content_hash != null && chapter.content_hash != now && chapter.content_hash == hashBeforeNames()
        // Reading the pages is slow: [whileLatest] asks again, around the write, whether this opening
        // still counts.
        return whileLatest {
            if (sameAsBefore) {
                database.chapterQueries.confirm(now, current)
                current
            } else {
                confirmContent(current, now)
            }
        }
    }

    /** The list of a work's chapters and the edition that stands for [chapterId] in it. */
    fun openingOf(workId: String, chapterId: String): ChapterOpeningList = database.transactionWithResult {
        ChapterOpeningList(currentEdition(chapterId), chapters(workId))
    }

    /** The chapter the work reopens in, or null when nothing of it was read yet. */
    fun resumeChapter(workId: String): StoredChapter? =
        database.workResumeQueries.resumeOfWork(workId, ::storedChapter).executeAsOneOrNull()

    /**
     * Everything stored about the reading, for the backup file: every work and chapter edition,
     * present or gone (rule E1), with its progress and where each work reopens, and the Kavita
     * servers. The address of the chosen folder stays out: it is not the same on another device.
     */
    fun backup(appVersion: String, exportedAt: Instant): Backup = database.transactionWithResult {
        // One transaction, so the copy is one moment of the database even while a scan runs.
        val works = database.workVersionQueries.allForBackup().executeAsList().map { version ->
            val resume = database.workResumeQueries.resumeOfVersion(version.id).executeAsOneOrNull()
            BackupWork(
                id = version.work_id,
                versionId = version.id,
                origin = version.origin,
                path = version.path,
                title = version.title,
                alternativeTitle = version.alternative_title,
                type = WorkType.valueOf(version.type),
                subtype = version.subtype?.let(ComicsSubtype::valueOf),
                present = version.present == 1L,
                source = version.source.takeIf { version.origin != LOCAL },
                remoteLibraryId = version.remote_library_id?.toInt(),
                alternatives = database.alternativePositionQueries.ofVersion(version.id).executeAsList().map {
                    BackupAlternative(it.reason, it.chapter_id, it.page.toInt(), it.total.toInt(), Instant.ofEpochMilli(it.at), it.device)
                },
                chapters = database.chapterQueries.allOfVersion(version.id).executeAsList().map { chapter ->
                    BackupChapter(
                        id = chapter.id,
                        replacedBy = chapter.replaced_by,
                        path = chapter.path,
                        name = chapter.name,
                        kind = UnitKind.valueOf(chapter.kind),
                        number = chapter.number,
                        format = ChapterFormat.valueOf(chapter.format),
                        contentHash = chapter.content_hash,
                        present = chapter.present == 1L,
                        resume = chapter.id == resume,
                        progress = progress(chapter.id),
                        pageCount = chapter.page_count?.toInt(),
                        remoteFile = chapter.remote_file,
                        remoteVolumeId = chapter.remote_volume_id?.toInt(),
                        agreement = database.progressAgreementQueries.ofChapter(chapter.id).executeAsOneOrNull()?.let {
                            BackupAgreement(it.page.toInt(), it.book_scroll_id, it.server_at, it.own_reread == 1L, it.local_cfi)
                        },
                        outbox = database.progressOutboxQueries.ofChapter(chapter.id).executeAsOneOrNull()?.let {
                            BackupOutbox(it.page.toInt(), it.book_scroll_id, it.local_cfi, it.state, Instant.ofEpochMilli(it.queued_at))
                        },
                    )
                },
            )
        }
        val servers = database.kavitaServerQueries.all().executeAsList().map { server ->
            val libraries = database.kavitaLibraryQueries.ofServer(server.id).executeAsList().map { it.toInt() }
            BackupServer(server.id, server.address, server.last_version, server.account_id?.toInt(), libraries)
        }
        Backup(appVersion, exportedAt, works, servers)
    }

    companion object {
        val schema: SqlSchema<QueryResult.Value<Unit>> = KamayoqDatabase.Schema

        const val KAVITA = "kavita"
        const val PREVIOUS = "PREVIOUS"
        const val CONFLICT = "CONFLICT"

        private const val LOCAL = "local"
        private const val TO_SEND = "TO_SEND"
        private const val SENT_UNCONFIRMED = "SENT_UNCONFIRMED"

        private fun formatOf(content: UnitContent): ChapterFormat = when (content) {
            UnitContent.Archive -> ChapterFormat.ARCHIVE
            is UnitContent.ImageFolder -> ChapterFormat.IMAGE_FOLDER
        }

        private fun syncItem(
            id: String,
            path: String,
            volume: Long?,
            pages: Long?,
            series: String,
            library: Long?,
            agreedPage: Long?,
            serverAt: String?,
            outboxState: String?,
            outboxPage: Long?,
            isEdition: Boolean,
        ) = SyncItem(
            id, path.toInt(), checkNotNull(volume).toInt(), series.toInt(), checkNotNull(library).toInt(),
            checkNotNull(pages).toInt(), agreedPage?.toInt(), serverAt, outboxState, outboxPage?.toInt(),
            isEdition,
        )

        private fun storedChapter(
            id: String,
            workId: String,
            path: String,
            name: String,
            kind: String,
            number: Double?,
            format: String,
            present: Long,
            needsContentCheck: Long,
            origin: String,
        ) = StoredChapter(
            id, workId, path, name, UnitKind.valueOf(kind), number, ChapterFormat.valueOf(format),
            present = present == 1L,
            needsContentCheck = needsContentCheck == 1L,
            origin = origin,
        )
    }
}
