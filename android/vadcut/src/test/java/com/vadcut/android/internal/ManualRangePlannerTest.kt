package com.vadcut.android.internal

import com.vadcut.android.AudioRange
import com.vadcut.android.ManualTrimPlan
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualRangePlannerTest {
    @Test
    fun removeModeSortsAndMergesOverlappingRanges() {
        val plan = ManualTrimPlan.removeRanges(
            AudioRange(4_000L, 6_000L),
            AudioRange(1_000L, 2_500L),
            AudioRange(2_000L, 4_500L),
        )

        assertEquals(
            listOf(TimeRangeUs(0L, 1_000_000L), TimeRangeUs(6_000_000L, 10_000_000L)),
            ManualRangePlanner.resolveKeptRanges(plan, 10_000_000L),
        )
    }

    @Test
    fun keepModeSortsAndMergesTouchingRanges() {
        val plan = ManualTrimPlan.keepRanges(
            AudioRange(5_000L, 8_000L),
            AudioRange(1_000L, 3_000L),
            AudioRange(3_000L, 5_000L),
        )

        assertEquals(
            listOf(TimeRangeUs(1_000_000L, 8_000_000L)),
            ManualRangePlanner.resolveKeptRanges(plan, 10_000_000L),
        )
    }

    @Test
    fun publicRoundedEndMapsBackToExactDecodedDuration() {
        val durationUs = 10_000_499L
        val plan = ManualTrimPlan.keepRanges(AudioRange(9_000L, 10_000L))

        assertEquals(
            listOf(TimeRangeUs(9_000_000L, durationUs)),
            ManualRangePlanner.resolveKeptRanges(plan, durationUs),
        )
    }

    @Test
    fun outOfBoundsRangeReturnsSpecificError() {
        val plan = ManualTrimPlan.removeRanges(AudioRange(9_000L, 10_001L))

        val error = assertThrows(TrimException::class.java) {
            ManualRangePlanner.resolveKeptRanges(plan, 10_000_000L)
        }
        assertEquals(TrimErrorCode.INVALID_TIME_RANGES, error.code)
    }

    @Test
    fun removingTheCompleteInputIsRejected() {
        val plan = ManualTrimPlan.removeRanges(AudioRange(0L, 10_000L))

        val error = assertThrows(TrimException::class.java) {
            ManualRangePlanner.resolveKeptRanges(plan, 10_000_000L)
        }
        assertEquals(TrimErrorCode.INVALID_TIME_RANGES, error.code)
    }
}
