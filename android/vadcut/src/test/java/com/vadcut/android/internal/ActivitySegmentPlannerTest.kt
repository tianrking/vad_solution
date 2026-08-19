package com.vadcut.android.internal

import com.vadcut.android.TrimConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivitySegmentPlannerTest {
    @Test
    fun hysteresisRejectsSilenceAndKeepsSpeech() {
        val config = TrimConfig.Builder()
            .setMinimumSpeechDurationMs(64)
            .setMinimumSilenceDurationMs(96)
            .setPaddingBeforeMs(0)
            .setPaddingAfterMs(0)
            .build()
        val planner = ActivitySegmentPlanner(config)
        val scores = listOf(0f, 0f, 0.8f, 0.8f, 0.8f, 0.8f, 0f, 0f, 0f, 0f)
        scores.forEachIndexed { index, score ->
            planner.accept(score, index * 32_000L, (index + 1) * 32_000L)
        }

        assertEquals(listOf(TimeRangeUs(64_000L, 192_000L)), planner.finish(320_000L))
    }

    @Test
    fun paddingMergesNearbyRanges() {
        val config = TrimConfig.Builder()
            .setMinimumSpeechDurationMs(0)
            .setMinimumSilenceDurationMs(32)
            .setPaddingBeforeMs(20)
            .setPaddingAfterMs(20)
            .build()
        val planner = ActivitySegmentPlanner(config)
        listOf(1f, 0f, 1f, 0f).forEachIndexed { index, score ->
            planner.accept(score, index * 32_000L, (index + 1) * 32_000L)
        }

        assertEquals(listOf(TimeRangeUs(0L, 116_000L)), planner.finish(128_000L))
    }
}
