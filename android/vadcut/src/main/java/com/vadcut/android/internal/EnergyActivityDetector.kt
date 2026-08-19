package com.vadcut.android.internal

import kotlin.math.log10
import kotlin.math.sqrt

internal class EnergyActivityDetector(
    private val thresholdDb: Float,
) : ActivityDetector {
    override fun score(samples: FloatArray, validSampleCount: Int): Float {
        if (validSampleCount <= 0) return 0f
        var sumSquares = 0.0
        for (index in 0 until validSampleCount) {
            val value = samples[index].coerceIn(-1f, 1f).toDouble()
            sumSquares += value * value
        }
        val rms = sqrt(sumSquares / validSampleCount).coerceAtLeast(1e-12)
        val db = (20.0 * log10(rms)).toFloat()
        return if (db >= thresholdDb) 1f else 0f
    }
}
