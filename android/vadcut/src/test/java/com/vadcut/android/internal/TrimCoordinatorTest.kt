package com.vadcut.android.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimCoordinatorTest {
    @Test
    fun complementReturnsEveryRemovedGap() {
        val kept = listOf(TimeRangeUs(100L, 200L), TimeRangeUs(300L, 450L))
        assertEquals(
            listOf(TimeRangeUs(0L, 100L), TimeRangeUs(200L, 300L), TimeRangeUs(450L, 500L)),
            TrimCoordinator.complement(kept, 500L),
        )
    }
}
