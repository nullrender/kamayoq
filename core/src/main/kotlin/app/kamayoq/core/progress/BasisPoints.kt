package app.kamayoq.core.progress

import kotlin.math.roundToInt

/**
 * How much of a reading unit has been read, as an integer from 0 (start) to 10 000 (end).
 * Integers keep comparisons exact: 1.30 % → 2.30 % is 130 → 230, a difference of exactly 100
 * (progress contract, section 2).
 */
@JvmInline
value class BasisPoints private constructor(val value: Int) {
    companion object {
        const val START = 0
        const val END = 10_000

        /**
         * Converts the fraction a reader reports (0.0 to 1.0) at the boundary, rounding to the
         * nearest integer. Missing or invalid input is refused (null), never turned into zero.
         */
        fun fromFraction(fraction: Double?): BasisPoints? {
            // NaN fails both comparisons, and infinity fails the upper bound, so both are refused.
            if (fraction == null || !(fraction >= 0.0 && fraction <= 1.0)) return null
            return BasisPoints((fraction * END).roundToInt())
        }
    }
}
