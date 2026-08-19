package com.vadcut.android.internal

internal data class AnalysisResult(
    val durationUs: Long,
    val keptRanges: List<TimeRangeUs>,
    val durationWasKnown: Boolean,
)
