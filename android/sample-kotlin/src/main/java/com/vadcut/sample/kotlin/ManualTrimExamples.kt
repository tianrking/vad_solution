package com.vadcut.sample.kotlin

import android.net.Uri
import com.vadcut.android.AudioRange
import com.vadcut.android.ManualTrimPlan
import com.vadcut.android.TrimConfig
import com.vadcut.android.TrimRequest

/** Compiled examples for caller-supplied ranges on the original input timeline. */
object ManualTrimExamples {
    fun removeRanges(
        input: Uri,
        output: Uri,
        rangesToDelete: List<AudioRange>,
    ): TrimRequest = TrimRequest.Builder(input, output)
        .setManualTrimPlan(ManualTrimPlan.removeRanges(rangesToDelete))
        .setConfig(TrimConfig.Builder().setFadeDurationMs(8L).build())
        .build()

    fun keepRanges(
        input: Uri,
        output: Uri,
        rangesToKeep: List<AudioRange>,
    ): TrimRequest = TrimRequest.Builder(input, output)
        .setKeptRanges(rangesToKeep)
        .setConfig(TrimConfig.Builder().setFadeDurationMs(8L).build())
        .build()
}
