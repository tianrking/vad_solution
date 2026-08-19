package com.vadcut.android.internal

internal data class TimeRangeUs(
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long
        get() = endUs - startUs
}
