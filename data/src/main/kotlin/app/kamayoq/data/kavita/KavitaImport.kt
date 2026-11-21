package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.KavitaTranslation
import app.kamayoq.core.kavita.RemoteWork
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.WorkCovers
import java.io.IOException

/**
 * Brings the marked libraries to the Library, whole, with what Kavita says about each work. [covers]
 * keeps each work's cover; without it, none is fetched.
 */
class KavitaImport(
    private val client: KavitaClient,
    private val store: LibraryStore,
    private val covers: WorkCovers? = null,
) {

    /**
     * Reads everything before writing anything: a failure halfway would otherwise take for gone what
     * was only not read yet. Throws what the client throws. Returns the pages read of each chapter,
     * by Kavita's id, which says what changed there with no question per chapter.
     */
    fun run(key: String): Map<String, Int> {
        val server = store.kavitaServer() ?: return emptyMap()
        val pagesRead = mutableMapOf<String, Int>()
        val libraries = client.libraries(server.address, key).filter { it.id in server.marked }
        val remotes = mutableListOf<RemoteWork>()
        val works = libraries.flatMap { library ->
            client.works(server.address, key, library.id).map { work ->
                remotes += work
                val chapters = client.chapters(server.address, key, work.id)
                chapters.forEach { pagesRead[it.id.toString()] = it.pagesRead }
                val details = KavitaTranslation.details(client.details(server.address, key, work.id))
                try {
                    KavitaTranslation.work(library, work, chapters).copy(details = details)
                } catch (_: IllegalArgumentException) {
                    throw KavitaListingFailed(HTTP_OK)
                }
            }
        }
        store.importKavita(server.id, works, markedAtStart = server.marked)
        covers?.let { keepCovers(it, server.id, server.address, key, remotes) }
        return pagesRead
    }

    // Only a new cover name, or a cover file gone, costs a download.
    private fun keepCovers(covers: WorkCovers, serverId: String, address: String, key: String, remotes: List<RemoteWork>) {
        val workIds = store.kavitaWorks(serverId)
        for (remote in remotes) {
            val workId = workIds[remote.id.toString()] ?: continue
            val stamp = remote.coverImage.ifEmpty { null }
            val stored = store.coverStamp(workId)
            if (stamp != null && stamp == stored && covers.file(workId) != null) continue
            // No cover there and none here: nothing to drop or record, on every import.
            if (stamp == null && stored == null && covers.file(workId) == null) continue
            try {
                val bytes = stamp?.let { client.cover(address, key, remote.id) }
                if (bytes == null) covers.drop(workId) else covers.keep(workId, bytes)
                store.saveCover(workId, stamp.takeIf { bytes != null })
            } catch (_: IOException) {
                // Fetching or writing it: the work keeps the cover it had, or the provisional one, and
                // its stamp stays as it was, so the next import asks again. One cover must not stop
                // the progress sync after it.
            }
        }
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
