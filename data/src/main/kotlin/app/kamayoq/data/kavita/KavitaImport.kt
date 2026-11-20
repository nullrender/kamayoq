package app.kamayoq.data.kavita

import app.kamayoq.core.kavita.KavitaTranslation
import app.kamayoq.data.LibraryStore
import app.kamayoq.data.WorkCovers

/**
 * Brings the marked libraries to the Library, whole, with what Kavita says about each work. [covers]
 * keeps each work's cover; without it, none is fetched.
 */
class KavitaImport(
    private val client: KavitaClient,
    private val store: LibraryStore,
    @Suppress("unused") private val covers: WorkCovers? = null,
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
        val works = libraries.flatMap { library ->
            client.works(server.address, key, library.id).map { work ->
                val chapters = client.chapters(server.address, key, work.id)
                chapters.forEach { pagesRead[it.id.toString()] = it.pagesRead }
                try {
                    KavitaTranslation.work(library, work, chapters)
                } catch (_: IllegalArgumentException) {
                    throw KavitaListingFailed(HTTP_OK)
                }
            }
        }
        store.importKavita(server.id, works, markedAtStart = server.marked)
        return pagesRead
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
