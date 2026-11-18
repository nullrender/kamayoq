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
import app.kamayoq.core.kavita.KavitaPlace
import app.kamayoq.core.kavita.KavitaRelink
import app.kamayoq.core.kavita.KavitaText
import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.ContentCheck
import app.kamayoq.core.library.FileStamp
import app.kamayoq.core.library.LocalLibrary
import app.kamayoq.core.library.ReadingUnit
import app.kamayoq.core.library.SplitFiles
import app.kamayoq.core.library.UnitContent
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.Work
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadingPosition
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import app.kamayoq.core.progress.strip
import app.kamayoq.core.reader.ContentsChapter
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
    /** Where it starts inside a file split by its table of contents; empty for a whole file. */
    val tocKey: String = "",
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
    /** A book: its page is a section, and a marker and a local CFI go with it (step 4, C2). */
    val isText: Boolean = false,
    val agreedMarker: String? = null,
    val agreedCfi: String? = null,
    val outboxMarker: String? = null,
    val outboxCfi: String? = null,
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
data class StoredResume(val chapterId: String, val position: ReadingPosition?)

/** The Kavita server of this app (one address only, by design), with the [marked] libraries. */
data class StoredServer(val id: String, val address: String, val marked: List<Int>)

/**
 * The question of a split file whose table of contents changed while its chapters had progress
 * (rule E1): [lastRead] is the last chapter read in the previous edition.
 */
data class EditionOffer(val path: String, val lastRead: String?)

/** What is stored of a split file: the stamp it had when its contents were read, and its chapters. */
data class StoredSplit(val stamp: String, val chapters: List<ContentsChapter>)

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

    /**
     * The server has this page too: the chapter leaves the queue. [ownReread] is rule C1. A book
     * also agrees on its [marker], and keeps the [localCfi] here that the agreed content stands for:
     * Kavita gives no CFI back.
     */
    fun agree(
        chapterId: String,
        page: Int,
        serverAt: String?,
        ownReread: Boolean,
        marker: String? = null,
        localCfi: String? = null,
    ) = database.transaction {
        database.progressAgreementQueries.agree(chapterId, page.toLong(), marker, serverAt, if (ownReread) 1L else 0L, localCfi)
        database.progressOutboxQueries.clear(chapterId)
    }

    /** The book is read at [localCfi] now, and its place there is still what was agreed. */
    fun moveAgreedCfi(chapterId: String, localCfi: String) = database.transaction {
        database.progressAgreementQueries.moveLocalCfi(localCfi, chapterId)
        database.progressOutboxQueries.clear(chapterId)
    }

    /** The marker agreed with Kavita, for a book that opens at a place from there. */
    fun agreedMarker(chapterId: String): String? =
        database.progressAgreementQueries.ofChapter(chapterId).executeAsOneOrNull()?.book_scroll_id

    /** Both sides are where they were: whatever was on its way no longer matters. */
    fun clearQueue(chapterId: String) {
        database.progressOutboxQueries.clear(chapterId)
    }

    /** Waiting for the network. What was sent and not confirmed stays as it was: rereading settles it. */
    fun queueProgress(chapterId: String, page: Int, at: Instant, marker: String? = null, localCfi: String? = null) = database.transaction {
        val queued = database.progressOutboxQueries.ofChapter(chapterId).executeAsOneOrNull()
        if (queued?.state != SENT_UNCONFIRMED) {
            database.progressOutboxQueries.queue(chapterId, page.toLong(), marker, localCfi, TO_SEND, at.toEpochMilli())
        }
    }

    /** Written before the request: a process that dies after it must reread, not send again. */
    fun markSending(chapterId: String, page: Int, at: Instant, marker: String? = null, localCfi: String? = null) {
        database.progressOutboxQueries.queue(chapterId, page.toLong(), marker, localCfi, SENT_UNCONFIRMED, at.toEpochMilli())
    }

    /**
     * Kavita's place of a book from there, as the server has it (decision C-2): the book reopens at
     * [place]'s marker, a read one stays read, and nothing unmarks (C1). A book's place is not kept
     * aside: the positions kept aside count images only.
     */
    fun adoptRemoteText(
        chapterId: String,
        place: KavitaPlace,
        pageCount: Int,
        serverAt: String?,
        at: Instant,
        moveResume: Boolean = true,
    ) {
        val remote = KavitaText.fromRemote(place, pageCount, at) ?: return
        database.transaction {
            val before = progress(chapterId)
            val mark = before.readMark ?: ReadMark(at, ReadMarkSource.KAVITA).takeIf { remote.read }
            writeProgress(chapterId, before.copy(readMark = mark, position = remote.position))
            val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
            if (moveResume) database.workResumeQueries.save(versionId, chapterId)
            // Kavita's own marker, even one the reader cannot find: the agreement is what the server has,
            // or the next sync would take it for news.
            agree(chapterId, place.page, serverAt, ownReread = false, place.marker, remote.position.cfi)
        }
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
            before.position?.strip()?.takeIf { it.page > 0 }?.let { keepAside(versionId, reason, chapterId, it) }
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
            // A book's place is not kept aside: the positions kept aside count images only.
            (progress(chapterId).position as? StripPosition)?.let { keepAside(versionId, CONFLICT, chapterId, it) }
        } else {
            (before?.position as? StripPosition)?.takeIf { it.page > 0 }?.let { keepAside(versionId, PREVIOUS, before.chapterId, it) }
            database.workResumeQueries.save(versionId, chapterId)
        }
    }

    /** The copy read now may not be the newest: the next opening with a server must compare again. */
    fun doubtContent(chapterId: String) {
        database.chapterQueries.doubt(chapterId)
    }

    /**
     * A conflict in a book settled for this device: the server's place becomes the agreement, so the
     * place read here goes next, with the marker it waits with in the queue: the queue stays. Not kept
     * aside: the positions kept aside count images only.
     */
    fun keepLocalText(chapterId: String, place: KavitaPlace, serverAt: String?) {
        database.progressAgreementQueries.agree(chapterId, place.page.toLong(), place.marker, serverAt, 0L, null)
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
                // meanwhile keeps the older letter case, of the same length. Inside a split file the
                // place is the path and the chapter's key.
                val pathNow = { stored: CurrentOfVersion -> work.path + stored.path.drop(work.path.length) }
                val current = database.chapterQueries.currentOfVersion(versionId).executeAsList()
                // The chapters of a split file are compared as a whole, not one by one (rule E1).
                val splitUnits = work.units.withIndex().filter { it.value.content == UnitContent.SplitEpub }.groupBy { it.value.path }
                val incomingPaths = work.units.map { it.path }.toSet()
                val storedByPath = current.groupBy(pathNow)
                // A file renamed only in letter case is the same file, as for any chapter; each
                // stored file serves one path.
                val renamedFiles = storedByPath.filterKeys { it !in incomingPaths }.entries
                    .groupByTo(mutableMapOf<String, MutableList<List<CurrentOfVersion>>>(), { it.key.lowercase(Locale.ROOT) }, { it.value })
                val storedOfSplit = splitUnits.keys.associateWith { path ->
                    storedByPath[path] ?: renamedFiles[path.lowercase(Locale.ROOT)]?.removeFirstOrNull() ?: emptyList()
                }
                val inSplitFiles = storedOfSplit.values.flatten().map { it.id }.toSet()
                val findChapter = PathMatch(
                    current.filter { it.id !in inSplitFiles },
                    work.units.filter { it.content != UnitContent.SplitEpub }.map { place(it.path, it.tocKey) },
                ) { place(pathNow(it), it.toc_href) }
                val chapterIds = work.units.mapIndexed { unitIndex, unit ->
                    if (unit.content == UnitContent.SplitEpub) return@mapIndexed null
                    val stamp = ContentCheck.stampOf(unit, stamps)
                    storeChapter(versionId, unit, unitIndex.toLong(), stamp, workCameBack, findChapter.of(place(unit.path, unit.tocKey)))
                }.filterNotNull() + splitUnits.flatMap { (path, units) ->
                    val stamp = ContentCheck.stampOf(units.first().value, stamps)
                    storeSplitFile(versionId, units.map { it.index.toLong() to it.value }, stamp, workCameBack, storedOfSplit.getValue(path))
                }
                database.chapterQueries.markAbsentExcept(versionId, chapterIds)
                versionId
            }
            database.workVersionQueries.markAbsentExcept(LOCAL, source, versionIds)
        }
    }

    /**
     * Brings the chapters of one split file in line with its [units], each with its place in the
     * work's order. Rule E1 for the whole file: the same titles in the same order keep every
     * chapter, with its reading, and only move the keys; any other contents is another edition, whose
     * chapters start with no reading, while the previous ones stay stored out of the lists. Then the
     * Work screen asks before any read mark passes, if there is one to pass. Returns the current
     * chapters, in order.
     */
    private fun storeSplitFile(
        versionId: String,
        units: List<Pair<Long, ReadingUnit>>,
        stamp: String,
        workCameBack: Boolean,
        stored: List<CurrentOfVersion>,
    ): List<String> {
        val path = units.first().second.path
        // A question still open goes with the file when its path changes.
        if (stored.isNotEmpty() && stored.first().path != path) database.editionOfferQueries.movePath(path, versionId, stored.first().path)
        // A file kept whole is one chapter named after the file: renamed, it is still that chapter,
        // as any chapter whose path changes only in letter case.
        val stillWhole = stored.size == 1 && units.size == 1 && stored[0].toc_href.isEmpty() && units[0].second.tocKey.isEmpty()
        if (stored.isEmpty() || stillWhole || stored.map { it.name } == units.map { it.second.name }) {
            val moving = stored.zip(units).filter { (row, unit) -> row.toc_href != unit.second.tocKey }
            // In two steps, so a key on its way never meets the same key still on another chapter.
            for ((row, _) in moving) database.chapterQueries.moveKey("\u0000${row.id}", row.id)
            for ((row, unit) in moving) database.chapterQueries.moveKey(unit.second.tocKey, row.id)
            return units.mapIndexed { index, (sortIndex, unit) ->
                storeChapter(versionId, unit, sortIndex, stamp, workCameBack, stored.getOrNull(index))
            }
        }
        val ids = units.map { newId() }
        val hadReadMark = stored.any { progress(it.id).readMark != null }
        val openQuestion = database.editionOfferQueries.isOpen(versionId, path).executeAsOne()
        for ((index, row) in stored.withIndex()) {
            // The chapter at the same destination takes its place; without one, the one at the same position.
            val sameKey = units.indexOfFirst { it.second.tocKey == row.toc_href }
            val heir = ids[if (sameKey >= 0) sameKey else minOf(index, ids.lastIndex)]
            // The edition an open question is about must stay one step from the current one, or
            // the question would lose sight of what was read.
            if (openQuestion) database.chapterQueries.repoint(heir, row.id)
            database.chapterQueries.replace(heir, row.id)
        }
        for ((id, entry) in ids.zip(units)) {
            val (sortIndex, unit) = entry
            database.chapterQueries.insert(
                id, versionId, unit.path, unit.name, unit.kind.name, unit.number, formatOf(unit.content).name, sortIndex, stamp, unit.tocKey,
            )
        }
        if (hadReadMark) database.editionOfferQueries.offer(versionId, path)
        return ids
    }

    /** The split files of [source] as stored, by path, for a reading of the folder to reuse. */
    fun splitFiles(source: String): Map<String, StoredSplit> =
        database.chapterQueries.splitOfSource(source).executeAsList().groupBy { it.path }.mapValues { (_, rows) ->
            // A file kept whole has one chapter with no key: it carries no division.
            StoredSplit(rows.first().stamp, rows.filter { it.toc_href.isNotEmpty() }.map { ContentsChapter(it.name, it.toc_href, volume = null) })
        }

    /** The current chapters of the file [chapterId] is in, in the order of its contents. */
    fun fileChapters(chapterId: String): List<StoredChapter> = database.transactionWithResult {
        val place = database.chapterQueries.placeOfChapter(currentEdition(chapterId)).executeAsOneOrNull()
            ?: return@transactionWithResult emptyList()
        chapters(place.work_id).filter { it.path == place.path }
    }

    /**
     * Brings the chapters of the file [chapterId] is in in line with [chapters], read from the file
     * as it opens, by the same rule as the import. Returns the chapter that stands for [chapterId].
     */
    fun resplit(chapterId: String, chapters: List<ContentsChapter>): String = database.transactionWithResult {
        val asked = currentEdition(chapterId)
        val place = database.chapterQueries.placeOfChapter(asked).executeAsOne()
        val stored = database.chapterQueries.currentOfVersion(place.version_id).executeAsList().filter { it.path == place.path }
        val stem = place.path.substringAfterLast('/').substringBeforeLast('.')
        val whole = ReadingUnit(place.path, stem, UnitKind.CHAPTER, null, UnitContent.SplitEpub)
        val units = SplitFiles.expand(LocalLibrary(listOf(Work(place.path, stem, null, WorkType.NOVELS, null, listOf(whole))), emptyList())) { chapters }
            .works.single().units
        // In the place the file has in the work: what comes after it moves by the difference.
        val first = stored.first().sort_index
        database.chapterQueries.shiftAfter((units.size - stored.size).toLong(), place.version_id, stored.last().sort_index)
        storeSplitFile(place.version_id, units.mapIndexed { index, unit -> first + index to unit }, stored.first().stamp, workCameBack = false, stored)
        currentEdition(asked)
    }

    /** The questions a work's changed files ask (rule E1). */
    fun editionOffers(workId: String): List<EditionOffer> = database.transactionWithResult {
        database.editionOfferQueries.ofWork(workId).executeAsList().map { offer ->
            val lastRead = database.chapterQueries.previousOfFile(offer.version_id, offer.path).executeAsList()
                .lastOrNull { progress(it.id).readMark != null }
            EditionOffer(offer.path, lastRead?.name)
        }
    }

    /** Marks read, in the new edition of the file at [path], the chapters read in the previous one, by title. */
    fun applyPreviousEdition(workId: String, path: String) = database.transaction {
        for (offer in database.editionOfferQueries.ofWork(workId).executeAsList().filter { it.path == path }) {
            val current = database.chapterQueries.currentOfVersion(offer.version_id).executeAsList().filter { it.path == path }.toMutableList()
            for (previous in database.chapterQueries.previousOfFile(offer.version_id, path).executeAsList()) {
                val readMark = progress(previous.id).readMark ?: continue
                // Each chapter of the new edition takes one mark, so two chapters of the same title keep their order.
                val target = current.firstOrNull { it.name == previous.name } ?: continue
                current.remove(target)
                val before = progress(target.id)
                // The place does not pass: it points into the other edition's text.
                if (before.readMark == null) writeProgress(target.id, before.copy(readMark = readMark))
            }
            database.editionOfferQueries.settle(offer.version_id, path)
        }
    }

    /** Ends the question without applying anything; the previous edition stays stored. */
    fun dismissEdition(workId: String, path: String) = database.transaction {
        for (offer in database.editionOfferQueries.ofWork(workId).executeAsList().filter { it.path == path }) {
            database.editionOfferQueries.settle(offer.version_id, path)
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
                database.progressAgreementQueries.agree(relinked.id, 0L, null, null, 0L, null)
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
                id, versionId, unit.path, unit.name, kind, unit.number, format, sortIndex, stamp, unit.tocKey,
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
        database.chapterProgressQueries.progressOf(chapterId) { readAt, readSource, page, total, at, device, reads, fraction, cfi ->
            ChapterProgress(
                readMark = readAt?.let {
                    ReadMark(Instant.ofEpochMilli(it), ReadMarkSource.valueOf(checkNotNull(readSource)))
                },
                position = page?.let {
                    // The database keeps both of a book's columns or neither, so the CFI tells them apart.
                    if (cfi != null) {
                        TextPosition(
                            section = it.toInt(),
                            sections = checkNotNull(total).toInt(),
                            cfi = cfi,
                            fraction = BasisPoints.fromStored(checkNotNull(fraction).toInt()),
                            at = Instant.ofEpochMilli(checkNotNull(at)),
                            device = checkNotNull(device),
                        )
                    } else {
                        StripPosition(
                            page = it.toInt(),
                            totalPages = checkNotNull(total).toInt(),
                            at = Instant.ofEpochMilli(checkNotNull(at)),
                            device = checkNotNull(device),
                        )
                    }
                },
                completeReads = reads.toInt(),
            )
        }.executeAsOneOrNull() ?: ChapterProgress.NEVER_OPENED

    /** Saves the reading of a chapter; that chapter becomes where its work reopens. */
    /**
     * [marker] is Kavita's name of the element a book's page starts at. Kavita gives no CFI back, so
     * a book from there keeps the marker of each place in the queue, beside its CFI: the next sync
     * sends that pair (step 4, C2).
     */
    fun saveProgress(chapterId: String, progress: ChapterProgress, marker: String? = null) {
        database.transaction {
            val before = progress(chapterId)
            writeProgress(chapterId, progress)
            val versionId = database.chapterQueries.versionOfChapter(chapterId).executeAsOne()
            database.workResumeQueries.save(versionId, chapterId)
            val place = progress.position as? TextPosition
            if (place != null && syncItem(chapterId)?.isText == true) {
                // Only the end block counts one more complete read (TextReading.ended): that save, and
                // no other, queues the book read (decision C-3). The read mark stays through a reread.
                val ended = progress.completeReads > before.completeReads
                queueProgress(chapterId, if (ended) place.sections else place.section, place.at, marker, place.cfi)
            }
        }
    }

    private fun writeProgress(chapterId: String, progress: ChapterProgress) {
        val position = progress.position
        // A book keeps its section and sections where a chapter keeps its page and total.
        val (page, total) = when (position) {
            null -> null to null
            is StripPosition -> position.page to position.totalPages
            is TextPosition -> position.section to position.sections
        }
        val book = position as? TextPosition
        database.chapterProgressQueries.save(
            chapterId,
            progress.readMark?.at?.toEpochMilli(),
            progress.readMark?.source?.name,
            page?.toLong(),
            total?.toLong(),
            position?.at?.toEpochMilli(),
            position?.device,
            progress.completeReads.toLong(),
            book?.fraction?.value?.toLong(),
            book?.cfi,
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
        if (chapter.toc_href.isNotEmpty()) {
            // A chapter of a split file: what tells its editions apart is the file's contents, at the
            // import, never the hash. The hash is of the whole file, so it confirms every chapter.
            database.chapterQueries.confirmFile(hash, chapter.version_id, chapter.path)
            return@transactionWithResult current
        }
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
                        tocKey = chapter.toc_href,
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
            UnitContent.Epub, UnitContent.SplitEpub -> ChapterFormat.EPUB
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
            format: String,
            agreedMarker: String?,
            agreedCfi: String?,
            outboxMarker: String?,
            outboxCfi: String?,
            isEdition: Boolean,
        ) = SyncItem(
            id, path.toInt(), checkNotNull(volume).toInt(), series.toInt(), checkNotNull(library).toInt(),
            checkNotNull(pages).toInt(), agreedPage?.toInt(), serverAt, outboxState, outboxPage?.toInt(),
            isEdition, ChapterFormat.valueOf(format) == ChapterFormat.EPUB, agreedMarker, agreedCfi, outboxMarker, outboxCfi,
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
            tocKey: String,
        ) = StoredChapter(
            id, workId, path, name, UnitKind.valueOf(kind), number, ChapterFormat.valueOf(format),
            present = present == 1L,
            needsContentCheck = needsContentCheck == 1L,
            origin = origin,
            tocKey = tocKey,
        )

        // A path never holds a NUL, so a path and a key cannot run into each other.
        private fun place(path: String, tocKey: String) = if (tocKey.isEmpty()) path else "$path\u0000$tocKey"
    }
}
