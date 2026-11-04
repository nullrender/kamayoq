package app.kamayoq.core.kavita

/**
 * Rule 8b: what Kavita gave a new id may be what vanished, with its progress kept here. Only a single
 * candidate links (decisions of 01/11 and 02/11/2026, docs/decisions/kavita-step-3-2026-10-30.md):
 * a wrong link would put the reading on another chapter, and no link only leaves it stored (rule E1).
 */
object KavitaRelink {
    data class Gone(val id: String, val number: Double?, val pages: Int, val file: String?)

    data class Arrived(val path: String, val number: Double?, val pages: Int, val file: String?)

    data class GoneWork(val versionId: String, val libraryId: Int?, val title: String)

    data class ArrivedWork(val path: String, val libraryId: Int, val title: String)

    /** The stored chapter each arrived chapter is, by arrived path. */
    fun chapters(gone: List<Gone>, arrived: List<Arrived>): Map<String, String> {
        val goneByKey = gone.groupBy { it.number to it.pages }
        return arrived.groupBy { it.number to it.pages }.flatMap { (key, arrivals) ->
            val candidates = goneByKey[key].orEmpty()
            val single = candidates.singleOrNull()
            val arrival = arrivals.singleOrNull()
            // With no number the page count says little: two books may have the same, so the name decides.
            if (key.first != null && single != null && arrival != null) {
                listOf(arrival.path to single.id)
            } else {
                unique(arrivals, Arrived::file, candidates, Gone::file).map { (new, old) -> new.path to old.id }
            }
        }.toMap()
    }

    /** The stored version each arrived series is, by arrived path. */
    fun works(gone: List<GoneWork>, arrived: List<ArrivedWork>): Map<String, String> =
        unique(arrived, { it.libraryId to it.title }, gone, { it.libraryId to it.title })
            .associate { (new, old) -> new.path to old.versionId }

    /** Pairs whose key appears exactly once on each side; a null key never pairs. */
    private fun <A, B> unique(a: List<A>, keyOfA: (A) -> Any?, b: List<B>, keyOfB: (B) -> Any?): List<Pair<A, B>> {
        val aByKey = a.groupBy(keyOfA)
        val bByKey = b.groupBy(keyOfB)
        return aByKey.mapNotNull { (key, sameA) ->
            val onlyA = sameA.singleOrNull()
            val onlyB = bByKey[key]?.singleOrNull()
            if (key != null && onlyA != null && onlyB != null) onlyA to onlyB else null
        }
    }
}
