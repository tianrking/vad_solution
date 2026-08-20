package com.vadcut.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualTrimPlanTest {
    @Test
    fun planDefensivelyCopiesAndExposesAnUnmodifiableList() {
        val source = mutableListOf(AudioRange(1_000L, 2_000L))
        val plan = ManualTrimPlan.removeRanges(source)
        source.clear()

        assertEquals(listOf(AudioRange(1_000L, 2_000L)), plan.ranges)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.ranges as MutableList<AudioRange>).add(AudioRange(3_000L, 4_000L))
        }
    }

    @Test
    fun planRejectsEmptyAndZeroDurationRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualTrimPlan.removeRanges(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualTrimPlan.keepRanges(AudioRange(2_000L, 2_000L))
        }
    }
}
