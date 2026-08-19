package com.vadcut.android.internal

import com.vadcut.android.TrimConfig

internal class ActivitySegmentPlanner(private val config: TrimConfig) {
    private val rawRanges = mutableListOf<TimeRangeUs>()
    private var active = false
    private var segmentStartUs = 0L
    private var lastActivityEndUs = 0L

    fun accept(score: Float, frameStartUs: Long, frameEndUs: Long) {
        if (!active) {
            if (score >= config.speechStartThreshold) {
                active = true
                segmentStartUs = frameStartUs
                lastActivityEndUs = frameEndUs
            }
            return
        }

        if (score >= config.speechEndThreshold) {
            lastActivityEndUs = frameEndUs
            return
        }

        val quietDurationUs = frameEndUs - lastActivityEndUs
        if (quietDurationUs >= config.minimumSilenceDurationMs * 1_000L) {
            commitCurrentRange()
            active = false
        }
    }

    fun finish(durationUs: Long): List<TimeRangeUs> {
        if (active) {
            commitCurrentRange()
            active = false
        }
        if (rawRanges.isEmpty() || durationUs <= 0L) return emptyList()

        val beforeUs = config.paddingBeforeMs * 1_000L
        val afterUs = config.paddingAfterMs * 1_000L
        val padded = rawRanges.mapNotNull { range ->
            val start = (range.startUs - beforeUs).coerceAtLeast(0L)
            val end = (range.endUs + afterUs).coerceAtMost(durationUs)
            if (end > start) TimeRangeUs(start, end) else null
        }

        val merged = mutableListOf<TimeRangeUs>()
        for (range in padded) {
            val previous = merged.lastOrNull()
            if (previous == null || range.startUs > previous.endUs) {
                merged += range
            } else {
                merged[merged.lastIndex] = TimeRangeUs(previous.startUs, maxOf(previous.endUs, range.endUs))
            }
        }
        return merged
    }

    private fun commitCurrentRange() {
        if (lastActivityEndUs - segmentStartUs >= config.minimumSpeechDurationMs * 1_000L) {
            rawRanges += TimeRangeUs(segmentStartUs, lastActivityEndUs)
        }
    }
}
