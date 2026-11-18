package app.kamayoq.core.backup

import app.kamayoq.core.library.ChapterFormat
import app.kamayoq.core.library.UnitKind
import app.kamayoq.core.library.WorkType
import app.kamayoq.core.progress.BasisPoints
import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.progress.ReadMark
import app.kamayoq.core.progress.ReadMarkSource
import app.kamayoq.core.progress.StripPosition
import app.kamayoq.core.progress.TextPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupFormatTest {

    private val readAt = Instant.parse("2026-10-25T22:14:03Z")
    private val stoppedAt = Instant.parse("2026-10-27T23:40:51Z")

    private fun chapter(
        path: String,
        progress: ChapterProgress,
        contentHash: String? = null,
        present: Boolean = true,
        resume: Boolean = false,
        id: String = "id:$path:$contentHash",
        replacedBy: String? = null,
        format: ChapterFormat = ChapterFormat.ARCHIVE,
    ) = BackupChapter(
        id = id,
        replacedBy = replacedBy,
        path = path,
        name = path.substringAfterLast('/').substringBeforeLast('.'),
        kind = UnitKind.CHAPTER,
        number = path.filter(Char::isDigit).toDoubleOrNull(),
        format = format,
        contentHash = contentHash,
        present = present,
        resume = resume,
        progress = progress,
    )

    private val read = ChapterProgress(
        readMark = ReadMark(readAt, ReadMarkSource.READ_TO_END),
        position = StripPosition(page = 42, totalPages = 42, at = readAt, device = "phone-a"),
        completeReads = 1,
    )

    private val halfway = ChapterProgress(
        readMark = null,
        position = StripPosition(page = 17, totalPages = 38, at = stoppedAt, device = "phone-a"),
        completeReads = 0,
    )

    private fun work(
        title: String = "Sakamoto Days",
        present: Boolean = true,
        chapters: List<BackupChapter>,
    ) = BackupWork(
        id = "work-$title",
        versionId = "version-$title",
        origin = "local",
        path = "Comics/$title",
        title = title,
        alternativeTitle = null,
        type = WorkType.COMICS,
        subtype = null,
        present = present,
        chapters = chapters,
    )

    private fun backup(vararg works: BackupWork) =
        Backup(appVersion = "r98", exportedAt = Instant.parse("2026-10-28T21:48:00Z"), works = works.toList())

    @Test
    fun `dado um capítulo lido e outro pela metade, quando gravados e relidos, então voltam iguais`() {
        val original = backup(
            work(
                chapters = listOf(
                    chapter("Comics/Sakamoto Days/Capítulo 1.cbz", read, contentHash = "ab12"),
                    chapter("Comics/Sakamoto Days/Capítulo 2.cbz", halfway, resume = true),
                ),
            ),
        )

        assertEquals(original, BackupFormat.read(BackupFormat.write(original)))
    }

    @Test
    fun `dado uma cópia gravada, então o arquivo diz a versão do formato, 3`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())))

        assertEquals(3, Json.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive?.int)
    }

    @Test
    fun `dado um título com aspas e acento, quando gravado, então se lê no arquivo e volta igual`() {
        val title = "Dom \"Casmurro\" — Ação"
        val original = backup(work(title = title, chapters = emptyList()))

        val text = BackupFormat.write(original)

        // Accents stay readable in the file, not escaped as \u codes.
        assertTrue("Ação" in text, text)
        assertEquals(title, BackupFormat.read(text).works.single().title)
    }

    @Test
    fun `dado uma obra que sumiu da pasta e uma edição anterior, quando gravadas e relidas, então voltam com o progresso e a ligação entre elas`() {
        val original = backup(
            work(
                present = false,
                chapters = listOf(
                    chapter(
                        "Comics/Sakamoto Days/Capítulo 1.cbz", read, contentHash = "old1", present = false,
                        id = "edition-1", replacedBy = "edition-2",
                    ),
                    chapter("Comics/Sakamoto Days/Capítulo 1.cbz", ChapterProgress.NEVER_OPENED, contentHash = "new1", id = "edition-2"),
                ),
            ),
        )

        val back = BackupFormat.read(BackupFormat.write(original))

        assertEquals(original, back)
        assertEquals(listOf("edition-2", null), back.works.single().chapters.map { it.replacedBy })
    }

    @Test
    fun `dado um capítulo em pasta de imagens, quando gravado e relido, então volta como pasta de imagens`() {
        val original = backup(
            work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 3", halfway, format = ChapterFormat.IMAGE_FOLDER))),
        )

        assertEquals(ChapterFormat.IMAGE_FOLDER, BackupFormat.read(BackupFormat.write(original)).works.single().chapters.single().format)
    }

    @Test
    fun `dado um servidor com bibliotecas marcadas e uma obra do Kavita, quando gravados e relidos, então voltam iguais`() {
        val chapter = chapter("91", read).copy(
            format = ChapterFormat.EPUB,
            pageCount = 42,
            remoteFile = "Vol. 1.epub",
            remoteVolumeId = 7,
            agreement = BackupAgreement(page = 42, bookScrollId = "id(\"x\")", serverAt = "2026-10-25T14:10:00", ownReread = false, localCfi = "epubcfi(/6/2)"),
            outbox = BackupOutbox(page = 17, bookScrollId = null, localCfi = null, state = "TO_SEND", queuedAt = stoppedAt),
        )
        val original = Backup(
            appVersion = "r98",
            exportedAt = stoppedAt,
            servers = listOf(BackupServer("server-1", "http://kavita.example:5000", "0.9.1.4", accountId = 1, libraries = listOf(2, 5))),
            works = listOf(
                work(chapters = listOf(chapter)).copy(
                    origin = "kavita",
                    source = "server-1",
                    path = "12",
                    remoteLibraryId = 2,
                    alternatives = listOf(BackupAlternative("CONFLICT", chapter.id, page = 3, total = 42, at = readAt, device = "kavita")),
                ),
            ),
        )

        assertEquals(original, BackupFormat.read(BackupFormat.write(original)))
    }

    // Format 1 as the app wrote it from 28/10/2026: no servers, no Kavita fields, no alternatives.
    private val format1 = """
        {
            "formatVersion": 1,
            "appVersion": "r98",
            "exportedAt": "2026-10-28T21:48:00Z",
            "works": [
                {
                    "id": "work-Sakamoto Days",
                    "versionId": "version-Sakamoto Days",
                    "origin": "local",
                    "path": "Comics/Sakamoto Days",
                    "title": "Sakamoto Days",
                    "alternativeTitle": null,
                    "type": "COMICS",
                    "subtype": null,
                    "present": true,
                    "chapters": [
                        {
                            "id": "chapter-1",
                            "replacedBy": null,
                            "path": "Comics/Sakamoto Days/Capítulo 1.cbz",
                            "name": "Capítulo 1",
                            "kind": "CHAPTER",
                            "number": 1.0,
                            "format": "ARCHIVE",
                            "contentHash": "ab12",
                            "present": true,
                            "resume": true,
                            "readMark": null,
                            "position": { "page": 17, "totalPages": 38, "at": "2026-10-27T23:40:51Z", "device": "phone-a" },
                            "completeReads": 0
                        }
                    ]
                }
            ]
        }
    """.trimIndent()

    private val sakamotoInFormat1 = Backup(
        appVersion = "r98",
        exportedAt = Instant.parse("2026-10-28T21:48:00Z"),
        works = listOf(
            work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 1.cbz", halfway, contentHash = "ab12", resume = true, id = "chapter-1"))),
        ),
    )

    @Test
    fun `dado um arquivo do formato 1, quando lido, então entra sem perda`() {
        assertEquals(sakamotoInFormat1, BackupFormat.read(format1))
    }

    @Test
    fun `dado um arquivo do formato 2 com servidor e obra do Kavita, quando lido, então entra sem perda`() {
        // Format 2 as the app wrote it from 02/11/2026: format 1 plus servers and the Kavita fields.
        val format2 = """
            {
                "formatVersion": 2,
                "appVersion": "r402",
                "exportedAt": "2026-11-08T23:00:00Z",
                "servers": [ { "id": "server-1", "address": "http://kavita.example:5000", "lastVersion": "0.9.1.4", "accountId": 1, "libraries": [2, 5] } ],
                "works": [
                    {
                        "id": "work-12",
                        "versionId": "version-12",
                        "origin": "kavita",
                        "path": "12",
                        "title": "Sakamoto Days",
                        "alternativeTitle": null,
                        "type": "COMICS",
                        "subtype": null,
                        "present": true,
                        "chapters": [
                            {
                                "id": "chapter-91",
                                "replacedBy": null,
                                "path": "91",
                                "name": "91",
                                "kind": "CHAPTER",
                                "number": 91.0,
                                "format": "ARCHIVE",
                                "contentHash": null,
                                "present": true,
                                "resume": false,
                                "readMark": { "at": "2026-10-25T22:14:03Z", "source": "READ_TO_END" },
                                "position": { "page": 42, "totalPages": 42, "at": "2026-10-25T22:14:03Z", "device": "phone-a" },
                                "completeReads": 1,
                                "pageCount": 42,
                                "remoteFile": "Vol. 1.cbz",
                                "remoteVolumeId": 7,
                                "agreement": { "page": 42, "bookScrollId": null, "serverAt": "2026-10-25T14:10:00", "ownReread": false, "localCfi": null },
                                "outbox": { "page": 17, "bookScrollId": null, "localCfi": null, "state": "TO_SEND", "queuedAt": "2026-10-27T23:40:51Z" }
                            }
                        ],
                        "source": "server-1",
                        "remoteLibraryId": 2,
                        "alternatives": [ { "reason": "CONFLICT", "chapterId": "chapter-91", "page": 3, "total": 42, "at": "2026-10-25T22:14:03Z", "device": "kavita" } ]
                    }
                ]
            }
        """.trimIndent()
        val chapter = chapter("91", read, id = "chapter-91").copy(
            pageCount = 42,
            remoteFile = "Vol. 1.cbz",
            remoteVolumeId = 7,
            agreement = BackupAgreement(page = 42, bookScrollId = null, serverAt = "2026-10-25T14:10:00", ownReread = false, localCfi = null),
            outbox = BackupOutbox(page = 17, bookScrollId = null, localCfi = null, state = "TO_SEND", queuedAt = stoppedAt),
        )
        val expected = Backup(
            appVersion = "r402",
            exportedAt = Instant.parse("2026-11-08T23:00:00Z"),
            servers = listOf(BackupServer("server-1", "http://kavita.example:5000", "0.9.1.4", accountId = 1, libraries = listOf(2, 5))),
            works = listOf(
                work(chapters = listOf(chapter)).copy(
                    id = "work-12",
                    versionId = "version-12",
                    origin = "kavita",
                    path = "12",
                    source = "server-1",
                    remoteLibraryId = 2,
                    alternatives = listOf(BackupAlternative("CONFLICT", "chapter-91", page = 3, total = 42, at = readAt, device = "kavita")),
                ),
            ),
        )

        assertEquals(expected, BackupFormat.read(format2))
    }

    @Test
    fun `dado um capítulo nunca aberto, quando gravado e relido, então volta sem marca e sem posição`() {
        val original = backup(work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 3.cbz", ChapterProgress.NEVER_OPENED))))

        assertEquals(ChapterProgress.NEVER_OPENED, BackupFormat.read(BackupFormat.write(original)).works.single().chapters.single().progress)
    }

    @Test
    fun `dado um arquivo de uma versão do formato que este app não conhece, quando lido, então recusa`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())))
            .replaceFirst("\"formatVersion\": 3", "\"formatVersion\": 4")

        assertTrue("\"formatVersion\": 4" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado uma posição de livro e um capítulo pela metade, quando exporto, então CFI e fração estão no arquivo e voltam iguais`() {
        val place = ChapterProgress(
            readMark = null,
            position = TextPosition(
                section = 2,
                sections = 148,
                cfi = "epubcfi(/6/6!/4/2[cap-2]/10/1:42)",
                fraction = BasisPoints.fromStored(137),
                at = stoppedAt,
                device = "phone-a",
            ),
            completeReads = 0,
        )
        val original = backup(
            work(title = "Dom Casmurro", chapters = listOf(chapter("Books/Dom Casmurro.epub", place, format = ChapterFormat.EPUB))),
            work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 2.cbz", halfway))),
        )

        val text = BackupFormat.write(original)

        val book = Json.parseToJsonElement(text).jsonObject["works"]!!.jsonArray[0].jsonObject["chapters"]!!.jsonArray[0].jsonObject
        val written = book["textPosition"]!!.jsonObject
        assertEquals("epubcfi(/6/6!/4/2[cap-2]/10/1:42)", written["cfi"]?.jsonPrimitive?.content)
        assertEquals(137, written["fraction"]?.jsonPrimitive?.int)
        assertEquals(original, BackupFormat.read(text))
    }

    @Test
    fun `dado um capítulo com página e lugar de livro ao mesmo tempo, quando lido, então recusa em vez de escolher um`() {
        val text = BackupFormat.write(backup(work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 2.cbz", halfway)))))
            .replaceFirst(
                "\"textPosition\": null",
                "\"textPosition\": { \"section\": 2, \"sections\": 148, \"cfi\": \"epubcfi(/6/6!)\", \"fraction\": 137, " +
                    "\"at\": \"2026-10-27T23:40:51Z\", \"device\": \"phone-a\" }",
            )

        assertTrue("\"section\": 2" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    // An earlier file is brought up to this format by adding the fields it could not have: a field
    // already there was not written by that format, and replacing it would drop it without a word.
    @Test
    fun `dado um arquivo que diz formato 2 mas traz lugar de livro, quando lido, então recusa em vez de apagar o lugar`() {
        val place = ChapterProgress(
            readMark = null,
            position = TextPosition(2, 148, "epubcfi(/6/6!/4/2[cap-2]/10/1:42)", BasisPoints.fromStored(137), stoppedAt, "phone-a"),
            completeReads = 0,
        )
        val text = BackupFormat.write(backup(work(chapters = listOf(chapter("Books/Dom Casmurro.epub", place, format = ChapterFormat.EPUB)))))
            .replaceFirst("\"formatVersion\": 3", "\"formatVersion\": 2")

        assertTrue("\"formatVersion\": 2" in text && "\"section\": 2" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado um arquivo que diz formato 1 mas traz servidores, quando lido, então recusa em vez de apagá-los`() {
        val text = format1.replaceFirst(
            "\"works\": [",
            "\"servers\": [ { \"id\": \"server-1\", \"address\": \"http://kavita.example:5000\", \"lastVersion\": null, " +
                "\"accountId\": 1, \"libraries\": [2] } ],\n    \"works\": [",
        )

        assertTrue("\"servers\"" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado 28 de outubro às 18h48 em São Paulo, então o arquivo se chama kamayoq_2026-10-28_18-48`() {
        val at = Instant.parse("2026-10-28T21:48:30Z")

        assertEquals("kamayoq_2026-10-28_18-48.json", BackupFormat.fileName(at, ZoneId.of("America/Sao_Paulo")))
    }
}
