package app.kamayoq.core.backup

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything the app knows about the reading, except the works' files (rule of 28/10/2026). Works
 * and editions gone from the folder go too, with their progress (rule E1).
 */
data class Backup(
    val appVersion: String,
    val exportedAt: Instant,
    val works: List<BackupWork>,
    val servers: List<BackupServer> = emptyList(),
)

/** A Kavita server and the [libraries] marked in it. The key never comes here: it stays in the device. */
data class BackupServer(
    val id: String,
    val address: String,
    val lastVersion: String?,
    val accountId: Int?,
    val libraries: List<Int>,
)

/**
 * A work as found in one origin. [id] and [versionId] are the app's own identifiers, never changed
 * (progress contract, section 3): restoring keeps them. [path] starts inside `Library/`, the same
 * on every device; for Kavita it is the series id. [source] is the [BackupServer] of a Kavita work
 * and null for a local one: the chosen folder is not the same on another device.
 */
data class BackupWork(
    val id: String,
    val versionId: String,
    val origin: String,
    val path: String,
    val title: String,
    val alternativeTitle: String?,
    val type: WorkType,
    val subtype: ComicsSubtype?,
    val present: Boolean,
    val chapters: List<BackupChapter>,
    val source: String? = null,
    val remoteLibraryId: Int? = null,
    val alternatives: List<BackupAlternative> = emptyList(),
)

/** A position the work could go back to, in the app's own identifiers. */
data class BackupAlternative(
    val reason: String,
    val chapterId: String,
    val page: Int,
    val total: Int,
    val at: Instant,
    val device: String,
)

/**
 * One edition of a chapter. A previous edition shares its [path] with the current one and names
 * the edition that took its place in [replacedBy]; the current one has none (rule E1). [id] is the
 * app's own identifier, never changed, the same on every device. [present] is the chapter's own:
 * a chapter of a work gone from the folder keeps the one it had. [resume] marks the chapter the
 * work reopens in.
 */
data class BackupChapter(
    val id: String,
    val replacedBy: String?,
    val path: String,
    val name: String,
    val kind: UnitKind,
    val number: Double?,
    val format: ChapterFormat,
    val contentHash: String?,
    val present: Boolean,
    val resume: Boolean,
    val progress: ChapterProgress,
    val pageCount: Int? = null,
    val remoteFile: String? = null,
    val remoteVolumeId: Int? = null,
    val agreement: BackupAgreement? = null,
    val outbox: BackupOutbox? = null,
)

/** The last position both sides agreed on (progress contract, section 2d). */
data class BackupAgreement(
    val page: Int,
    val bookScrollId: String?,
    val serverAt: String?,
    val ownReread: Boolean,
    val localCfi: String?,
)

/** What still had to reach Kavita. */
data class BackupOutbox(
    val page: Int,
    val bookScrollId: String?,
    val localCfi: String?,
    val state: String,
    val queuedAt: Instant,
)

/** The backup file: JSON, so a person can read it, with a format version (decision of 27/10/2026). */
object BackupFormat {
    const val VERSION = 2

    // Pretty and with accents as they are, so the file reads in any text editor. Nulls are written
    // out, so every field shows even when empty.
    private val json = Json { prettyPrint = true }

    private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    fun write(backup: Backup): String = json.encodeToString(BackupFile.serializer(), BackupFile.of(backup))

    /** The backup in [text]. Throws [IllegalArgumentException] for a file this version cannot read. */
    fun read(text: String): Backup {
        // The version is read first: a later format may change any other field (LNReader reads
        // every version as its first one).
        // Bad JSON and a missing field are SerializationException, an IllegalArgumentException.
        val version = json.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive?.intOrNull
        require(version == VERSION) { "Backup format $version is not $VERSION" }
        return try {
            json.decodeFromString(BackupFile.serializer(), text).toBackup()
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("A time in the backup is not ISO 8601", e)
        }
    }

    /** `kamayoq_<yyyy-mm-dd_hh-mm>.json`, in the person's time. */
    fun fileName(at: Instant, zone: ZoneId): String = "kamayoq_${fileTime.format(at.atZone(zone))}.json"
}

// The file's own shape, apart from the app's types, so a change in the app never changes the file
// without a new format version. Times are ISO 8601 in UTC; enums are written by name.

@Serializable
private class BackupFile(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val servers: List<ServerEntry>,
    val works: List<WorkEntry>,
) {
    fun toBackup() = Backup(appVersion, Instant.parse(exportedAt), works.map { it.toWork() }, servers.map { it.toServer() })

    companion object {
        fun of(backup: Backup) = BackupFile(
            formatVersion = BackupFormat.VERSION,
            appVersion = backup.appVersion,
            exportedAt = backup.exportedAt.toString(),
            servers = backup.servers.map(ServerEntry::of),
            works = backup.works.map(WorkEntry::of),
        )
    }
}

@Serializable
private class ServerEntry(
    val id: String,
    val address: String,
    val lastVersion: String?,
    val accountId: Int?,
    val libraries: List<Int>,
) {
    fun toServer() = BackupServer(id, address, lastVersion, accountId, libraries)

    companion object {
        fun of(server: BackupServer) =
            ServerEntry(server.id, server.address, server.lastVersion, server.accountId, server.libraries)
    }
}

@Serializable
private class WorkEntry(
    val id: String,
    val versionId: String,
    val origin: String,
    val path: String,
    val title: String,
    val alternativeTitle: String?,
    val type: String,
    val subtype: String?,
    val present: Boolean,
    val chapters: List<ChapterEntry>,
    val source: String?,
    val remoteLibraryId: Int?,
    val alternatives: List<AlternativeEntry>,
) {
    fun toWork() = BackupWork(
        id = id,
        versionId = versionId,
        origin = origin,
        path = path,
        title = title,
        alternativeTitle = alternativeTitle,
        type = WorkType.valueOf(type),
        subtype = subtype?.let(ComicsSubtype::valueOf),
        present = present,
        chapters = chapters.map { it.toChapter() },
        source = source,
        remoteLibraryId = remoteLibraryId,
        alternatives = alternatives.map { it.toAlternative() },
    )

    companion object {
        fun of(work: BackupWork) = WorkEntry(
            id = work.id,
            versionId = work.versionId,
            origin = work.origin,
            path = work.path,
            title = work.title,
            alternativeTitle = work.alternativeTitle,
            type = work.type.name,
            subtype = work.subtype?.name,
            present = work.present,
            chapters = work.chapters.map(ChapterEntry::of),
            source = work.source,
            remoteLibraryId = work.remoteLibraryId,
            alternatives = work.alternatives.map(AlternativeEntry::of),
        )
    }
}

@Serializable
private class AlternativeEntry(
    val reason: String,
    val chapterId: String,
    val page: Int,
    val total: Int,
    val at: String,
    val device: String,
) {
    fun toAlternative() = BackupAlternative(reason, chapterId, page, total, Instant.parse(at), device)

    companion object {
        fun of(position: BackupAlternative) = AlternativeEntry(
            position.reason, position.chapterId, position.page, position.total, position.at.toString(), position.device,
        )
    }
}

@Serializable
private class ChapterEntry(
    val id: String,
    val replacedBy: String?,
    val path: String,
    val name: String,
    val kind: String,
    val number: Double?,
    val format: String,
    val contentHash: String?,
    val present: Boolean,
    val resume: Boolean,
    val readMark: ReadMarkEntry?,
    val position: PositionEntry?,
    val completeReads: Int,
    val pageCount: Int?,
    val remoteFile: String?,
    val remoteVolumeId: Int?,
    val agreement: AgreementEntry?,
    val outbox: OutboxEntry?,
) {
    fun toChapter() = BackupChapter(
        id = id,
        replacedBy = replacedBy,
        path = path,
        name = name,
        kind = UnitKind.valueOf(kind),
        number = number,
        format = ChapterFormat.valueOf(format),
        contentHash = contentHash,
        present = present,
        resume = resume,
        progress = ChapterProgress(
            readMark = readMark?.let { ReadMark(Instant.parse(it.at), ReadMarkSource.valueOf(it.source)) },
            position = position?.let { StripPosition(it.page, it.totalPages, Instant.parse(it.at), it.device) },
            completeReads = completeReads,
        ),
        pageCount = pageCount,
        remoteFile = remoteFile,
        remoteVolumeId = remoteVolumeId,
        agreement = agreement?.let { BackupAgreement(it.page, it.bookScrollId, it.serverAt, it.ownReread, it.localCfi) },
        outbox = outbox?.let { BackupOutbox(it.page, it.bookScrollId, it.localCfi, it.state, Instant.parse(it.queuedAt)) },
    )

    companion object {
        fun of(chapter: BackupChapter) = ChapterEntry(
            id = chapter.id,
            replacedBy = chapter.replacedBy,
            path = chapter.path,
            name = chapter.name,
            kind = chapter.kind.name,
            number = chapter.number,
            format = chapter.format.name,
            contentHash = chapter.contentHash,
            present = chapter.present,
            resume = chapter.resume,
            readMark = chapter.progress.readMark?.let { ReadMarkEntry(it.at.toString(), it.source.name) },
            position = chapter.progress.position?.let {
                PositionEntry(it.page, it.totalPages, it.at.toString(), it.device)
            },
            completeReads = chapter.progress.completeReads,
            pageCount = chapter.pageCount,
            remoteFile = chapter.remoteFile,
            remoteVolumeId = chapter.remoteVolumeId,
            agreement = chapter.agreement?.let {
                AgreementEntry(it.page, it.bookScrollId, it.serverAt, it.ownReread, it.localCfi)
            },
            outbox = chapter.outbox?.let {
                OutboxEntry(it.page, it.bookScrollId, it.localCfi, it.state, it.queuedAt.toString())
            },
        )
    }
}

@Serializable
private class ReadMarkEntry(val at: String, val source: String)

@Serializable
private class PositionEntry(val page: Int, val totalPages: Int, val at: String, val device: String)

@Serializable
private class AgreementEntry(
    val page: Int,
    val bookScrollId: String?,
    val serverAt: String?,
    val ownReread: Boolean,
    val localCfi: String?,
)

@Serializable
private class OutboxEntry(
    val page: Int,
    val bookScrollId: String?,
    val localCfi: String?,
    val state: String,
    val queuedAt: String,
)
