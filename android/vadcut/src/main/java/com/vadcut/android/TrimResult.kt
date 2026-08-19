package com.vadcut.android

import android.net.Uri

data class TrimResult(
    val outputUri: Uri,
    val inputDurationMs: Long,
    val outputDurationMs: Long,
    val removedDurationMs: Long,
    val keptRanges: List<AudioRange>,
    val removedRanges: List<AudioRange>,
    val warnings: List<TrimWarning>,
)
