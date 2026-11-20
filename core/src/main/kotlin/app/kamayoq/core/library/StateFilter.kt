package app.kamayoq.core.library

import app.kamayoq.core.progress.ReadingState

/**
 * The chips above the Library (FEATURES, part 3, and the Library board): every work, one of the
 * five states, or the works with none. One is chosen at a time.
 */
enum class StateFilter(val state: ReadingState?) {
    ALL(null),
    READING(ReadingState.READING),
    WANT_TO_READ(ReadingState.WANT_TO_READ),
    ON_HOLD(ReadingState.ON_HOLD),
    DROPPED(ReadingState.DROPPED),
    COMPLETED(ReadingState.COMPLETED),
    NONE(null),
    ;

    companion object {
        /** How many of [states] each chip shows, every chip present, zero included. */
        fun counts(states: List<ReadingState?>): Map<StateFilter, Int> =
            entries.associateWith { chip -> states.count(chip::matches) }
    }

    /** The items whose state, by [stateOf], this chip keeps, in the order given. */
    fun <T> keep(items: List<T>, stateOf: (T) -> ReadingState?): List<T> = items.filter { matches(stateOf(it)) }

    // NONE and ALL both carry no state; only ALL takes every work.
    private fun matches(state: ReadingState?): Boolean = this == ALL || state == this.state
}
