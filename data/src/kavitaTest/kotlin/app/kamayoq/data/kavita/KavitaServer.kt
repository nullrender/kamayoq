package app.kamayoq.data.kavita

import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class KavitaAnswer(val code: Int, val body: String)

internal data class KavitaWork(val id: Int, val name: String, val libraryId: Int)

internal data class KavitaChapter(
    val id: Int,
    val volumeId: Int,
    val number: String,
    val pages: Int,
    val pagesRead: Int,
    val maxNumber: Double,
    val isSpecial: Boolean,
    val files: List<KavitaFile>,
)

internal data class KavitaFile(val path: String, val bytes: Long) {
    val name get() = path.substringAfterLast('/')
}

/** The Kavita of this run, shared by every test of the suite. */
internal object KavitaServer {
    // Fails instead of skipping: a silent skip would look like a Kavita that answered.
    val url: String = System.getenv("KAVITA_URL")
        ?: error("KAVITA_URL is not set: this suite needs a real Kavita")

    private val http = OkHttpClient()

    // Created once per run. It lives only in this process and is never printed.
    val authKey: String by lazy { prepare() }

    val libraryId: Int by lazy { prepareLibrary("Test library", "/manga") }

    val mutableLibraryId: Int by lazy { prepareLibrary("Mutable library", "/mutable") }

    val bookLibraryId: Int by lazy { prepareLibrary("Book library", "/books", type = 2, fileGroup = 2, metadata = true) }

    // Kept from the moment of the setup: the test library appears as soon as any test needs it.
    lateinit var librariesAfterSetup: KavitaAnswer
        private set

    fun get(path: String, key: String? = null): KavitaAnswer = headers(path, key).first

    /** The answer and its Cache-Control header. */
    fun headers(path: String, key: String? = null): Pair<KavitaAnswer, String?> {
        val request = Request.Builder().url("$url$path")
            .apply { if (key != null) header("x-api-key", key) }
            .build()
        return http.newCall(request).execute().use {
            KavitaAnswer(it.code, it.body.string()) to it.header("Cache-Control")
        }
    }

    fun works(libraryId: Int = this.libraryId): List<KavitaWork> {
        val inTheLibrary = buildJsonObject {
            putJsonArray("statements") {
                addJsonObject {
                    put("field", 19)
                    put("comparison", 0)
                    put("value", libraryId.toString())
                }
            }
            put("combination", 1)
        }
        return post("/api/Series/v2?PageNumber=1&PageSize=50", inTheLibrary, "x-api-key" to authKey)
            .jsonArray.map {
                val work = it.jsonObject
                KavitaWork(
                    work.getValue("id").jsonPrimitive.int,
                    work.getValue("name").jsonPrimitive.content,
                    work.getValue("libraryId").jsonPrimitive.int,
                )
            }
    }

    fun chapters(workId: Int): List<KavitaChapter> {
        val volumes = get("/api/Series/volumes?seriesId=$workId", key = authKey)
        return Json.parseToJsonElement(volumes.body).jsonArray.flatMap { volume ->
            volume.jsonObject.getValue("chapters").jsonArray.map {
                val chapter = it.jsonObject
                KavitaChapter(
                    chapter.getValue("id").jsonPrimitive.int,
                    chapter.getValue("volumeId").jsonPrimitive.int,
                    chapter.getValue("number").jsonPrimitive.content,
                    chapter.getValue("pages").jsonPrimitive.int,
                    chapter.getValue("pagesRead").jsonPrimitive.int,
                    chapter.getValue("maxNumber").jsonPrimitive.double,
                    chapter.getValue("isSpecial").jsonPrimitive.boolean,
                    chapter.getValue("files").jsonArray.map { file ->
                        KavitaFile(
                            file.jsonObject.getValue("filePath").jsonPrimitive.content,
                            file.jsonObject.getValue("bytes").jsonPrimitive.long,
                        )
                    },
                )
            }
        }
    }

    /** Saves as the web reader does, with the date a slower device would have sent. */
    fun saveProgress(work: KavitaWork, chapter: KavitaChapter, page: Int, lastModifiedUtc: String? = null) {
        val progress = buildJsonObject {
            put("volumeId", chapter.volumeId)
            put("chapterId", chapter.id)
            put("pageNum", page)
            put("seriesId", work.id)
            put("libraryId", work.libraryId)
            if (lastModifiedUtc != null) put("lastModifiedUtc", lastModifiedUtc)
        }
        send("/api/Reader/progress", progress, "x-api-key" to authKey)
    }

    /** The number of each volume, in the order the server gives them. */
    fun volumeNumbers(workId: Int): List<Double> =
        Json.parseToJsonElement(get("/api/Series/volumes?seriesId=$workId", key = authKey).body).jsonArray
            .map { it.jsonObject.getValue("minNumber").jsonPrimitive.double }

    fun progress(chapter: KavitaChapter): JsonObject =
        Json.parseToJsonElement(get("/api/Reader/get-progress?chapterId=${chapter.id}", key = authKey).body).jsonObject

    /** Scans again and returns when the scanner is idle: a scan asked for while another runs waits 3 hours. */
    fun rescan(libraryId: Int) {
        waitForIdleScanner()
        val before = lastScanned(libraryId)
        send("/api/Library/scan?libraryId=$libraryId", buildJsonObject {}, "x-api-key" to authKey)
        waitForScan(libraryId, before)
    }

    private fun prepare(): String {
        check(get("/api/Admin/exists").body == "false") { "This Kavita already has an administrator" }
        val token = post(
            "/api/Account/register",
            buildJsonObject {
                put("username", "ci" + UUID.randomUUID().toString().take(8))
                put("password", "Aa1!" + UUID.randomUUID())
            },
        ).jsonObject.getValue("token").jsonPrimitive.content
        val key = post(
            "/api/Account/create-auth-key",
            buildJsonObject {
                put("keyLength", 32)
                put("name", "ci")
            },
            "Authorization" to "Bearer $token",
        ).jsonObject.getValue("key").jsonPrimitive.content
        librariesAfterSetup = get("/api/Library/libraries", key = key)
        return key
    }

    private fun prepareLibrary(name: String, folder: String, type: Int = 0, fileGroup: Int = 1, metadata: Boolean = false): Int {
        waitForIdleScanner()
        val created = post(
            "/api/Library/create",
            buildJsonObject {
                put("name", name)
                put("type", type)
                putJsonArray("folders") { add(folder) }
                put("folderWatching", false)
                put("includeInDashboard", true)
                put("includeInSearch", true)
                put("manageCollections", false)
                put("manageReadingLists", false)
                put("allowScrobbling", false)
                put("allowMetadataMatching", false)
                put("enableMetadata", metadata)
                put("removePrefixForSortName", false)
                put("inheritWebLinksFromFirstChapter", false)
                put("defaultLanguage", "")
                put("metadataProvider", 3)
                putJsonArray("fileGroupTypes") { add(fileGroup) }
                putJsonArray("excludePatterns") {}
            },
            "x-api-key" to authKey,
        ).jsonObject
        val id = created.getValue("id").jsonPrimitive.int
        // Creating the library already queues a scan; asking for another would delay it by 3 hours.
        waitForScan(id, before = created.getValue("lastScanned").jsonPrimitive.content)
        return id
    }

    private fun waitForScan(libraryId: Int, before: String) {
        val start = TimeSource.Monotonic.markNow()
        while (lastScanned(libraryId) == before) {
            check(start.elapsedNow() < 120.seconds) { "The scan did not end in 120 s" }
            Thread.sleep(500)
        }
        waitForIdleScanner()
        println("scan took ${start.elapsedNow()}")
    }

    private fun waitForIdleScanner() {
        val start = TimeSource.Monotonic.markNow()
        while (scannerIsBusy()) {
            check(start.elapsedNow() < 120.seconds) { "The scanner stayed busy for 120 s" }
            Thread.sleep(500)
        }
    }

    private fun lastScanned(libraryId: Int): String {
        val libraries = get("/api/Library/libraries", key = authKey)
        return Json.parseToJsonElement(libraries.body).jsonArray
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.int == libraryId }
            .getValue("lastScanned").jsonPrimitive.content
    }

    // Any answer but true or false stops the run: read as idle, it would start a scan over a scan.
    private fun scannerIsBusy(): Boolean {
        val answer = get("/api/Server/is-task-running?methodName=ScanLibrary&queue=scan", key = authKey)
        check(answer.code == 200 && answer.body in setOf("true", "false")) {
            "The scanner state is unknown: ${answer.code} ${answer.body}"
        }
        return answer.body == "true"
    }

    private fun post(path: String, body: JsonObject, vararg headers: Pair<String, String>): JsonElement =
        Json.parseToJsonElement(send(path, body, *headers).body)

    private fun send(path: String, body: JsonObject, vararg headers: Pair<String, String>): KavitaAnswer {
        val request = Request.Builder().url("$url$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val answer = http.newCall(request).execute().use { KavitaAnswer(it.code, it.body.string()) }
        // Only a refusal is shown: a successful answer carries the token or the key.
        check(answer.code == 200) { "$path answered ${answer.code}: ${answer.body}" }
        return answer
    }
}
