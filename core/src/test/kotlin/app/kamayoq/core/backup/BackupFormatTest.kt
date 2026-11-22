package app.kamayoq.core.backup

import app.kamayoq.core.library.ChapterFormat
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
import app.kamayoq.core.reader.FooterContent
import app.kamayoq.core.reader.ImageModeChoice
import app.kamayoq.core.reader.PageTheme
import app.kamayoq.core.reader.ReaderFlow
import app.kamayoq.core.reader.ReadingFont
import app.kamayoq.core.reader.TextLookChoices
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
    fun `dado uma cópia gravada, então o arquivo diz a versão do formato, 7`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())))

        assertEquals(7, Json.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive?.int)
    }

    private val states = listOf(
        BackupReadingState(ReadingState.READING, BackupAutomaticChange(from = ReadingState.WANT_TO_READ, at = stoppedAt), mark = null),
        BackupReadingState(ReadingState.ON_HOLD, automatic = null, mark = StateMark("chapter-x", BasisPoints.fromStored(3_000))),
        BackupReadingState(state = null, BackupAutomaticChange(from = null, at = readAt), mark = null),
    )

    @Test
    fun `dado obras com estado, mudança automática e marca de livro, quando gravadas e relidas, então voltam iguais`() {
        val original = backup(
            *states.mapIndexed { i, state -> work(title = "Obra $i", chapters = emptyList()).copy(readingState = state) }.toTypedArray(),
            work(title = "Sem estado", chapters = emptyList()),
        )

        val text = BackupFormat.write(original)

        assertTrue("\"state\": \"ON_HOLD\"" in text, text)
        assertEquals(original, BackupFormat.read(text))
    }

    @Test
    fun `dado um arquivo do formato 4, quando lido, então as obras chegam sem estado`() {
        val original = backup(work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 2.cbz", halfway))))
        val format4 = BackupFormat.write(original)
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 4")
            .replace(Regex(""",\s*"readingState": null"""), "")
            .withoutFormat6()

        assertTrue("\"formatVersion\": 4" in format4 && "readingState" !in format4, format4)
        assertEquals(original, BackupFormat.read(format4))
    }

    @Test
    fun `dado um arquivo que diz formato 4 mas traz o estado de uma obra, quando lido, então recusa em vez de apagá-lo`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList()).copy(readingState = states.first())))
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 4")
            .withoutFormat6()

        assertTrue("\"formatVersion\": 4" in text && "readingState" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    private val compiled = "Novels/Release That Witch/Release That Witch.epub"

    private fun splitChapters() = listOf(
        chapter(compiled, read, id = "toc-1", format = ChapterFormat.EPUB).copy(name = "Capítulo 1", tocKey = "OEBPS/c1.xhtml"),
        chapter(compiled, ChapterProgress.NEVER_OPENED, id = "toc-2", format = ChapterFormat.EPUB, resume = true)
            .copy(name = "Capítulo 2", tocKey = "OEBPS/c2.xhtml#cap"),
    )

    @Test
    fun `dado dois capítulos do mesmo arquivo, quando gravados e relidos, então cada um volta com a sua chave`() {
        val original = backup(work(title = "Release That Witch", chapters = splitChapters()))

        val text = BackupFormat.write(original)

        assertTrue("\"tocKey\": \"OEBPS/c2.xhtml#cap\"" in text, text)
        assertEquals(original, BackupFormat.read(text))
    }

    @Test
    fun `dado um arquivo do formato 3, quando lido, então todo capítulo é o arquivo inteiro`() {
        val original = backup(work(chapters = listOf(chapter("Comics/Sakamoto Days/Capítulo 2.cbz", halfway))))
        val format3 = BackupFormat.write(original)
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 3")
            .replace(Regex(""",\s*"readingState": null"""), "")
            .withoutFormat6()
            .replace(Regex(""",\s*"tocKey": "[^"]*""""), "")

        assertTrue("\"formatVersion\": 3" in format3 && "tocKey" !in format3, format3)
        assertEquals(original, BackupFormat.read(format3))
    }

    @Test
    fun `dado um arquivo que diz formato 3 mas traz a chave de um capítulo, quando lido, então recusa em vez de apagá-la`() {
        val text = BackupFormat.write(backup(work(title = "Release That Witch", chapters = splitChapters())))
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 3")
            .replace(Regex(""",\s*"readingState": null"""), "")
            .withoutFormat6()

        assertTrue("\"formatVersion\": 3" in text && "tocKey" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
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
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 8")

        assertTrue("\"formatVersion\": 8" in text, text)
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
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 2")
            .replace(Regex(""",\s*"readingState": null"""), "")
            .withoutFormat6()

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

    // A file this version cannot read says so with IllegalArgumentException, whatever its format.
    @Test
    fun `dado um arquivo do formato 1 sem a lista de obras, quando lido, então recusa como arquivo ilegível`() {
        val text = """{ "formatVersion": 1, "appVersion": "r98", "exportedAt": "2026-10-28T21:48:00Z" }"""

        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado um arquivo do formato 2 com uma obra sem capítulos, quando lido, então recusa como arquivo ilegível`() {
        val text = """
            {
                "formatVersion": 2, "appVersion": "r402", "exportedAt": "2026-11-08T23:00:00Z", "servers": [],
                "works": [
                    {
                        "id": "work-1", "versionId": "version-1", "origin": "local", "path": "Comics/Sakamoto Days",
                        "title": "Sakamoto Days", "alternativeTitle": null, "type": "COMICS", "subtype": null,
                        "present": true, "source": null, "remoteLibraryId": null, "alternatives": []
                    }
                ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado 28 de outubro às 18h48 em São Paulo, então o arquivo se chama kamayoq_2026-10-28_18-48`() {
        val at = Instant.parse("2026-10-28T21:48:30Z")

        assertEquals("kamayoq_2026-10-28_18-48.json", BackupFormat.fileName(at, ZoneId.of("America/Sao_Paulo")))
    }

    // What a file of format 5 or before lacks: the text reader's settings, null when nothing is set.
    private fun String.withoutFormat6() = withoutFormat7().replace(Regex(""",\s*"textLook": null"""), "").replace(Regex(""",\s*"textMode": null"""), "")

    private val ownLook = TextLookChoices(font = ReadingFont.GARAMOND, size = 22, indent = false, paragraphSpacing = 8)
    private val generalLook = TextLookChoices(theme = PageTheme.SEPIA, justify = true, footer = FooterContent.PROGRESS, lineHeight = 16, margin = 28)

    @Test
    fun `dado ajustes gerais do leitor e os próprios de uma obra com o modo, quando gravados e relidos, então voltam iguais`() {
        val original = backup(
            work(title = "Lorde dos Mistérios", chapters = emptyList()).copy(type = WorkType.NOVELS, textLook = ownLook, textMode = ReaderFlow.PAGES),
            work(title = "Dom Casmurro", chapters = emptyList()).copy(type = WorkType.BOOKS),
        ).copy(textLook = generalLook)

        val text = BackupFormat.write(original)

        assertTrue("\"theme\": \"SEPIA\"" in text && "\"textMode\": \"PAGES\"" in text, text)
        assertEquals(original, BackupFormat.read(text))
    }

    @Test
    fun `dado um arquivo do formato 5, quando lido, então nada do leitor vem ajustado`() {
        val original = backup(work(chapters = emptyList()))
        val format5 = BackupFormat.write(original)
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 5")
            .withoutFormat6()

        assertTrue("\"formatVersion\": 5" in format5 && "textLook" !in format5, format5)
        val read = BackupFormat.read(format5)
        assertEquals(TextLookChoices.NONE, read.textLook)
        assertEquals(null, read.works.single().textLook)
        assertEquals(null, read.works.single().textMode)
    }

    @Test
    fun `dado um arquivo que diz formato 5 mas traz ajustes do leitor, quando lido, então recusa em vez de apagá-los`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())).copy(textLook = generalLook))
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 5")
            .withoutFormat7()

        assertTrue("\"formatVersion\": 5" in text && "\"textLook\": {" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado um ajuste do leitor com um nome ou um número que o app não conhece, quando lido, então recusa`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())).copy(textLook = generalLook))
        for (bad in listOf(text.replace("\"SEPIA\"", "\"NEON\""), text.replace("\"margin\": 28", "\"margin\": 90"))) {
            assertFailsWith<IllegalArgumentException> { BackupFormat.read(bad) }
        }
    }

    // What a file of format 6 or before lacks: the image reader's mode, null when nothing is chosen.
    private fun String.withoutFormat7() = replace(Regex(""",\s*"imageMode": null"""), "")

    @Test
    fun `dado o modo geral do leitor de imagens e o próprio de duas obras, quando gravados e relidos, então voltam iguais`() {
        val original = backup(
            work(title = "Kaoru Hana wa Rin to Saku", chapters = emptyList()).copy(imageMode = ImageModeChoice.STRIP),
            work(title = "Solo Leveling", chapters = emptyList()).copy(imageMode = ImageModeChoice.AUTOMATIC),
            work(title = "Sakamoto Days", chapters = emptyList()),
        ).copy(imageMode = ImageModeChoice.PAGES_LEFT_TO_RIGHT)

        val text = BackupFormat.write(original)

        assertTrue("\"imageMode\": \"PAGES_LEFT_TO_RIGHT\"" in text && "\"imageMode\": \"AUTOMATIC\"" in text, text)
        assertEquals(original, BackupFormat.read(text))
    }

    @Test
    fun `dado um arquivo do formato 6, quando lido, então nenhum modo do leitor de imagens vem escolhido`() {
        val original = backup(work(chapters = emptyList()))
        val format6 = BackupFormat.write(original)
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 6")
            .withoutFormat7()

        assertTrue("\"formatVersion\": 6" in format6 && "imageMode" !in format6, format6)
        val read = BackupFormat.read(format6)
        assertEquals(ImageModeChoice.AUTOMATIC, read.imageMode)
        assertEquals(null, read.works.single().imageMode)
    }

    @Test
    fun `dado um arquivo que diz formato 6 mas traz o modo do leitor de imagens, quando lido, então recusa em vez de apagá-lo`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())).copy(imageMode = ImageModeChoice.STRIP))
            .replaceFirst("\"formatVersion\": ${BackupFormat.VERSION}", "\"formatVersion\": 6")

        assertTrue("\"formatVersion\": 6" in text && "\"imageMode\": \"STRIP\"" in text, text)
        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text) }
    }

    @Test
    fun `dado um modo do leitor de imagens com um nome que o app não conhece, quando lido, então recusa`() {
        val text = BackupFormat.write(backup(work(chapters = emptyList())).copy(imageMode = ImageModeChoice.STRIP))

        assertFailsWith<IllegalArgumentException> { BackupFormat.read(text.replace("\"STRIP\"", "\"DOUBLE_PAGE\"")) }
    }
}
