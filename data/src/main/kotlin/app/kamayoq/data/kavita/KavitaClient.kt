package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.KavitaConnection
import app.kamayoq.core.kavita.RemoteChapter
import app.kamayoq.core.kavita.RemoteFile
import app.kamayoq.core.kavita.RemoteLibrary
import app.kamayoq.core.kavita.RemoteProgress
import app.kamayoq.core.kavita.RemoteWork
import app.kamayoq.core.kavita.normalizeKavitaAddress
import app.kamayoq.data.SyncItem
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Kavita answered a listing with something else than the list; [code] is its HTTP status. */
class KavitaListingFailed(val code: Int) : IOException("Kavita answered $code")

// Redirects stay off: OkHttp keeps x-api-key when it follows one to another host, and the key may
// only reach the address the person typed.
open class KavitaClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun connect(address: String, key: String): KavitaConnection {
        val url = "${normalizeKavitaAddress(address)}/api/Account".toHttpUrlOrNull()
            ?: return KavitaConnection.Unreachable
        val cleanKey = key.trim()
        // OkHttp throws on anything a header cannot carry; no real key has such a character.
        if (cleanKey.any { it !in ' '..'~' }) return KavitaConnection.Refused
        // The key goes only in this header, never in a URL, where logs could keep it. This route gives
        // the version to any account; Plugin/version wants the key in the URL (docs/decisions/kavita-step-3-2026-10-30.md).
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", cleanKey)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> connected(response.body.string()) ?: KavitaConnection.NotKavita
                    401, 403 -> KavitaConnection.Refused
                    else -> KavitaConnection.NotKavita
                }
            }
        } catch (_: IOException) {
            KavitaConnection.Unreachable
        }
    }

    open fun libraries(address: String, key: String): List<RemoteLibrary> {
        val answer = fetch(address, key, "/api/Library/libraries")
        return ofTheRightShape {
            answer.body.jsonArray.map {
                val library = it.jsonObject
                RemoteLibrary(library.int("id"), library.text("name"), library.int("type"))
            }
        }
    }

    fun works(address: String, key: String, libraryId: Int, pageSize: Int = 100): List<RemoteWork> {
        val inTheLibrary = buildJsonObject {
            putJsonArray("statements") {
                addJsonObject {
                    put("field", LIBRARIES_FIELD)
                    put("comparison", EQUAL)
                    put("value", libraryId.toString())
                }
            }
            put("combination", AND)
        }
        val works = mutableListOf<RemoteWork>()
        var page = 1
        do {
            val answer = fetch(address, key, "/api/Series/v2?PageNumber=$page&PageSize=$pageSize", inTheLibrary)
            ofTheRightShape {
                answer.body.jsonArray.mapTo(works) {
                    val work = it.jsonObject
                    RemoteWork(work.int("id"), work.text("name"), work.int("libraryId"))
                }
            }
            val pages = answer.totalPages ?: throw KavitaListingFailed(HTTP_OK)
        } while (page++ < pages)
        return works
    }

    fun chapters(address: String, key: String, workId: Int): List<RemoteChapter> {
        val answer = fetch(address, key, "/api/Series/volumes?seriesId=$workId")
        return ofTheRightShape {
            answer.body.jsonArray.flatMap { volume ->
                val volumeNumber = volume.jsonObject.double("minNumber")
                volume.jsonObject.getValue("chapters").jsonArray.map {
                    val chapter = it.jsonObject
                    RemoteChapter(
                        id = chapter.int("id"),
                        minNumber = chapter.double("minNumber"),
                        maxNumber = chapter.double("maxNumber"),
                        pages = chapter.int("pages"),
                        volumeId = chapter.int("volumeId"),
                        volumeNumber = volumeNumber,
                        isSpecial = chapter.getValue("isSpecial").jsonPrimitive.boolean,
                        titleName = chapter.text("titleName"),
                        files = chapter.getValue("files").jsonArray.map { file ->
                            val entry = file.jsonObject
                            RemoteFile(entry.text("filePath"), entry.int("format"), entry.getValue("bytes").jsonPrimitive.long)
                        },
                        pagesRead = chapter.int("pagesRead"),
                    )
                }
            }
        }
    }

    open fun progress(address: String, key: String, chapterId: Int): RemoteProgress {
        val answer = fetch(address, key, "/api/Reader/get-progress?chapterId=$chapterId")
        return ofTheRightShape {
            val record = answer.body.jsonObject
            // With no record the server invents an answer with no volume (KavitaFactsTest).
            val exists = record.int("volumeId") != 0
            RemoteProgress(record.int("pageNum"), if (exists) record.text("lastModifiedUtc") else null)
        }
    }

    open fun saveProgress(address: String, key: String, item: SyncItem, page: Int) {
        val progress = buildJsonObject {
            put("volumeId", item.volumeId)
            put("chapterId", item.remoteId)
            put("pageNum", page)
            put("seriesId", item.seriesId)
            put("libraryId", item.libraryId)
        }
        fetch(address, key, "/api/Reader/progress", progress, parsed = false)
    }

    /**
     * Writes the chapter's original file to [target]. Throws [IOException] or [KavitaListingFailed].
     * Stops with [IOException] as soon as [stillWanted] says no one waits for the file: a screen that
     * left must not hold the openings of others until its download ends.
     */
    open fun download(address: String, key: String, chapterId: String, target: File, stillWanted: () -> Boolean) {
        val url = "${normalizeKavitaAddress(address)}/api/Download/chapter?chapterId=$chapterId".toHttpUrlOrNull()
            ?: throw IOException("Not an address")
        val request = Request.Builder().url(url).header("x-api-key", key.trim()).build()
        http.newCall(request).execute().use { response ->
            if (response.code != HTTP_OK) throw KavitaListingFailed(response.code)
            target.outputStream().use { output ->
                val input = response.body.byteStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (!stillWanted()) throw IOException("No one waits for chapter $chapterId any more")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private class Fetched(val body: JsonElement, val totalPages: Int?)

    private fun fetch(address: String, key: String, path: String, filter: JsonObject? = null, parsed: Boolean = true): Fetched {
        val url = "${normalizeKavitaAddress(address)}$path".toHttpUrlOrNull() ?: throw IOException("Not an address")
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", key.trim())
            .apply { if (filter != null) post(filter.toString().toRequestBody(JSON)) }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != HTTP_OK) throw KavitaListingFailed(response.code)
            if (!parsed) return Fetched(JsonObject(emptyMap()), null)
            return ofTheRightShape {
                val pagination = response.header("Pagination")?.let { Json.parseToJsonElement(it).jsonObject }
                Fetched(Json.parseToJsonElement(response.body.string()), pagination?.int("totalPages"))
            }
        }
    }

    // Reading a JSON element as what it is not throws these; a bad parse is one of the first kind.
    private inline fun <T> ofTheRightShape(read: () -> T): T = try {
        read()
    } catch (_: IllegalArgumentException) {
        throw KavitaListingFailed(HTTP_OK)
    } catch (_: NoSuchElementException) {
        throw KavitaListingFailed(HTTP_OK)
    }

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double

    // .content alone would turn a null name into a library called "null".
    private fun JsonObject.text(name: String): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString) { "$name is not text" }
        return value.content
    }

    // Null when whatever answered is not Kavita: a 200 alone proves nothing. This answer also carries
    // tokens and keys: only these two fields are read from it.
    private fun connected(account: String): KavitaConnection.Connected? = try {
        val answer = Json.parseToJsonElement(account) as? JsonObject
        val version = (answer?.get("kavitaVersion") as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null }
        version?.let { KavitaConnection.Connected(it, accountId = (answer["id"] as? JsonPrimitive)?.intOrNull) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val HTTP_OK = 200
        val JSON = "application/json".toMediaType()

        // Kavita's series filter: SeriesFilterField.Libraries, FilterComparison.Equal, FilterCombination.And.
        const val LIBRARIES_FIELD = 19
        const val EQUAL = 0
        const val AND = 1
    }
}
