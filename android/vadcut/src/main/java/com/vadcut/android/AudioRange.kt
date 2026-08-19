package com.vadcut.android

/** A half-open time range: [startTimeMs, endTimeMs). */
data class AudioRange(
    val startTimeMs: Long,
    val endTimeMs: Long,
) {
    init {
        require(startTimeMs >= 0) { "startTimeMs must be >= 0" }
        require(endTimeMs >= startTimeMs) { "endTimeMs must be >= startTimeMs" }
    }

    val durationMs: Long
        get() = endTimeMs - startTimeMs
}
