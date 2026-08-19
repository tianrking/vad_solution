package com.vadcut.android

data class TrimProgress(
    val phase: TrimPhase,
    val percent: Int,
    val processedDurationMs: Long,
    val totalDurationMs: Long,
)
