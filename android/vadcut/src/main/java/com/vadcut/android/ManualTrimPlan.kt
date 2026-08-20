package com.vadcut.android

import java.util.Collections

/**
 * An immutable manual edit plan whose ranges use the original input timeline in milliseconds.
 *
 * Ranges are half-open: `[startTimeMs, endTimeMs)`. They may be supplied in any order and may
 * overlap or touch; VadCut sorts and merges them before export. Every range must have a positive
 * duration. Bounds that depend on the input duration are validated when the trim starts.
 */
class ManualTrimPlan private constructor(
    val mode: ManualTrimMode,
    ranges: List<AudioRange>,
) {
    val ranges: List<AudioRange> = Collections.unmodifiableList(ArrayList(ranges))

    init {
        require(ranges.isNotEmpty()) { "Manual trim ranges must not be empty" }
        ranges.forEach { range ->
            require(range.durationMs > 0L) { "Manual trim ranges must have a positive duration" }
        }
    }

    companion object {
        /** Creates a plan that deletes [ranges] and keeps every other part of the input. */
        @JvmStatic
        fun removeRanges(ranges: List<AudioRange>): ManualTrimPlan =
            ManualTrimPlan(ManualTrimMode.REMOVE_RANGES, ranges)

        /** Vararg convenience overload for Kotlin and Java. */
        @JvmStatic
        fun removeRanges(vararg ranges: AudioRange): ManualTrimPlan = removeRanges(ranges.asList())

        /** Creates a plan that keeps only [ranges] and deletes every other part of the input. */
        @JvmStatic
        fun keepRanges(ranges: List<AudioRange>): ManualTrimPlan =
            ManualTrimPlan(ManualTrimMode.KEEP_RANGES, ranges)

        /** Vararg convenience overload for Kotlin and Java. */
        @JvmStatic
        fun keepRanges(vararg ranges: AudioRange): ManualTrimPlan = keepRanges(ranges.asList())
    }
}
