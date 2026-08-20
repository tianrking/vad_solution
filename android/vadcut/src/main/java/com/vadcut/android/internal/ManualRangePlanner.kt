package com.vadcut.android.internal

import com.vadcut.android.AudioRange
import com.vadcut.android.ManualTrimMode
import com.vadcut.android.ManualTrimPlan
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException

/** Converts and validates public millisecond ranges against the decoded input duration. */
internal object ManualRangePlanner {
    fun resolveKeptRanges(plan: ManualTrimPlan, durationUs: Long): List<TimeRangeUs> {
        if (durationUs <= 0L) invalid("The decoded input duration must be positive")

        val normalized = normalize(plan.ranges, durationUs)
        val keptRanges = when (plan.mode) {
            ManualTrimMode.REMOVE_RANGES -> complement(normalized, durationUs)
            ManualTrimMode.KEEP_RANGES -> normalized
        }
        if (keptRanges.isEmpty()) {
            invalid("The manual trim plan removes the complete audio")
        }
        return keptRanges
    }

    private fun normalize(ranges: List<AudioRange>, durationUs: Long): List<TimeRangeUs> {
        val roundedDurationMs = usToRoundedMs(durationUs)
        val converted = ranges.map { range ->
            if (range.durationMs <= 0L) invalid("Manual trim ranges must have a positive duration")
            if (range.endTimeMs > roundedDurationMs) {
                invalid(
                    "Manual trim range [${range.startTimeMs}, ${range.endTimeMs}) exceeds " +
                        "the input duration of $roundedDurationMs ms",
                )
            }

            val startUs = range.startTimeMs * 1_000L
            val endUs = if (range.endTimeMs == roundedDurationMs) {
                durationUs
            } else {
                range.endTimeMs * 1_000L
            }
            if (startUs >= durationUs || endUs <= startUs) {
                invalid(
                    "Manual trim range [${range.startTimeMs}, ${range.endTimeMs}) is outside " +
                        "the input duration of $roundedDurationMs ms",
                )
            }
            TimeRangeUs(startUs, endUs.coerceAtMost(durationUs))
        }.sortedBy(TimeRangeUs::startUs)

        val merged = mutableListOf<TimeRangeUs>()
        for (range in converted) {
            val previous = merged.lastOrNull()
            if (previous == null || range.startUs > previous.endUs) {
                merged += range
            } else {
                merged[merged.lastIndex] = TimeRangeUs(previous.startUs, maxOf(previous.endUs, range.endUs))
            }
        }
        return merged
    }

    internal fun complement(ranges: List<TimeRangeUs>, durationUs: Long): List<TimeRangeUs> {
        val result = mutableListOf<TimeRangeUs>()
        var cursor = 0L
        for (range in ranges) {
            if (range.startUs > cursor) result += TimeRangeUs(cursor, range.startUs)
            cursor = maxOf(cursor, range.endUs)
        }
        if (cursor < durationUs) result += TimeRangeUs(cursor, durationUs)
        return result
    }

    internal fun usToRoundedMs(valueUs: Long): Long =
        valueUs / 1_000L + if (valueUs % 1_000L >= 500L) 1L else 0L

    private fun invalid(message: String): Nothing =
        throw TrimException(TrimErrorCode.INVALID_TIME_RANGES, message)
}
