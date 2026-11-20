package app.kamayoq.core.backup

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.ComicsSubtype
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.ReadingState
import app.kamayoq.core.progress.StateMark
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
    /** The work's reading state; null for a work that never had one. */
    val readingState: BackupReadingState? = null,
)

/**
 * A work's reading [state], null for none; [automatic] is the change the app made by itself and the
 * person has not settled; [mark] is where a book's reading was when the state was last decided.
 */
data class BackupReadingState(
    val state: ReadingState?,
    val automatic: BackupAutomaticChange?,
    val mark: StateMark?,
)

/** A change of state the app made by itself, at [at]; [from] is the state before, null for none. */
data class BackupAutomaticChange(val from: ReadingState?, val at: Instant)

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
    /** Where the chapter starts inside a file split by its table of contents; empty for a whole file. */
    val tocKey: String = "",
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
    /**
     * 4 carries a chapter's key in a split file (step 4, milestone B); 3 a book's place (piece 22);
     * 2 added Kavita (step 3); 1 is from 28/10/2026.
     */
    const val VERSION = 4

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
        val file = json.parseToJsonElement(text).jsonObject
        val version = file["formatVersion"]?.jsonPrimitive?.intOrNull
        require(version != null && version in 1..VERSION) { "Backup format $version is not between 1 and $VERSION" }
        // An earlier file is brought up to this format first, the way the database migrates, so
        // the current one is still read strictly: a field missing from a format 3 file is an error.
        var current = file
        if (version < 2) current = toFormat2(current)
        if (version < 3) current = toFormat3(current)
        if (version < 4) current = toFormat4(current)
        return try {
            json.decodeFromJsonElement(BackupFile.serializer(), current).toBackup()
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("A time in the backup is not ISO 8601", e)
        }
    }

    /** Format 2 added the Kavita servers and fields and the alternative positions, all empty in a format 1 file. */
    private fun toFormat2(file: JsonObject): JsonObject =
        file.adding("servers" to JsonArray(emptyList())).withWorks { work ->
            work.adding("source" to JsonNull, "remoteLibraryId" to JsonNull, "alternatives" to JsonArray(emptyList()))
                .withChapters { chapter ->
                    chapter.adding(
                        "pageCount" to JsonNull,
                        "remoteFile" to JsonNull,
                        "remoteVolumeId" to JsonNull,
                        "agreement" to JsonNull,
                        "outbox" to JsonNull,
                    )
                }
        }

    /** Format 3 added a book's place, which no earlier file could hold. */
    private fun toFormat3(file: JsonObject): JsonObject =
        file.withWorks { work -> work.withChapters { it.adding("textPosition" to JsonNull) } }

    /** Format 4 added the key of a chapter inside a split file; every chapter before it is a whole file. */
    private fun toFormat4(file: JsonObject): JsonObject =
        file.withWorks { work -> work.withChapters { it.adding("tocKey" to JsonPrimitive("")) } }

    // A field the declared format could not have was not written by it: replacing it would drop it
    // without a word, so the file is refused instead.
    private fun JsonObject.adding(vararg fields: Pair<String, JsonElement>): JsonObject {
        val later = fields.map { it.first }.filter { it in this }
        require(later.isEmpty()) { "An earlier backup format has fields of a later one: $later" }
        return JsonObject(this + fields)
    }

    private fun JsonObject.withWorks(change: (JsonObject) -> JsonObject) = changingEach("works", change)

    private fun JsonObject.withChapters(change: (JsonObject) -> JsonObject) = changingEach("chapters", change)

    // A list missing here is a file this version cannot read, said the same way as any other.
    private fun JsonObject.changingEach(list: String, change: (JsonObject) -> JsonObject): JsonObject {
        val items = requireNotNull(this[list]) { "The backup has no \"$list\"" }.jsonArray
        return JsonObject(this + (list to JsonArray(items.map { change(it.jsonObject) })))
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
    val textPosition: TextPositionEntry?,
    val tocKey: String,
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
            position = readPosition(),
            completeReads = completeReads,
        ),
        pageCount = pageCount,
        remoteFile = remoteFile,
        remoteVolumeId = remoteVolumeId,
        agreement = agreement?.let { BackupAgreement(it.page, it.bookScrollId, it.serverAt, it.ownReread, it.localCfi) },
        outbox = outbox?.let { BackupOutbox(it.page, it.bookScrollId, it.localCfi, it.state, Instant.parse(it.queuedAt)) },
        tocKey = tocKey,
    )

    // A chapter has a page in a strip or a place in a book, never both: a file with both was not
    // written by this app, and keeping one would drop the other without a word.
    private fun readPosition() = when {
        position != null && textPosition != null -> throw IllegalArgumentException("Chapter $id has both a page and a book's place")
        position != null -> StripPosition(position.page, position.totalPages, Instant.parse(position.at), position.device)
        textPosition != null -> TextPosition(
            textPosition.section,
            textPosition.sections,
            textPosition.cfi,
            BasisPoints.fromStored(textPosition.fraction),
            Instant.parse(textPosition.at),
            textPosition.device,
        )
        else -> null
    }

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
            position = (chapter.progress.position as? StripPosition)?.let {
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
            textPosition = (chapter.progress.position as? TextPosition)?.let {
                TextPositionEntry(it.section, it.sections, it.cfi, it.fraction.value, it.at.toString(), it.device)
            },
            tocKey = chapter.tocKey,
        )
    }
}

@Serializable
private class ReadMarkEntry(val at: String, val source: String)

@Serializable
private class PositionEntry(val page: Int, val totalPages: Int, val at: String, val device: String)

/** [fraction] in basis points, 0 to 10 000, as the app keeps it. */
@Serializable
private class TextPositionEntry(
    val section: Int,
    val sections: Int,
    val cfi: String,
    val fraction: Int,
    val at: String,
    val device: String,
)

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
