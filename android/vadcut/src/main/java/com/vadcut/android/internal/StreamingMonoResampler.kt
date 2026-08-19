package com.vadcut.android.internal

import kotlin.math.PI
import kotlin.math.exp

/** Constant-memory streaming linear resampler with a two-pole low-pass when downsampling. */
internal class StreamingMonoResampler(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val output: (Float) -> Unit,
) {
    private val step = inputSampleRate.toDouble() / outputSampleRate.toDouble()
    private val lowPassAlpha = if (inputSampleRate > outputSampleRate) {
        val cutoffHz = outputSampleRate * 0.45
        1.0 - exp(-2.0 * PI * cutoffHz / inputSampleRate)
    } else {
        1.0
    }

    private var inputIndex = -1L
    private var nextOutputPosition = 0.0
    private var previous = 0f
    private var filterOne = 0.0
    private var filterTwo = 0.0
    private var initialized = false

    fun accept(sample: Float) {
        val filtered = filter(sample.coerceIn(-1f, 1f))
        inputIndex += 1L
        if (!initialized) {
            initialized = true
            previous = filtered
            output(filtered)
            nextOutputPosition += step
            return
        }

        val previousPosition = inputIndex - 1.0
        while (nextOutputPosition <= inputIndex.toDouble()) {
            val fraction = (nextOutputPosition - previousPosition).coerceIn(0.0, 1.0)
            output((previous + (filtered - previous) * fraction.toFloat()).coerceIn(-1f, 1f))
            nextOutputPosition += step
        }
        previous = filtered
    }

    private fun filter(sample: Float): Float {
        if (lowPassAlpha >= 1.0) return sample
        if (!initialized) {
            filterOne = sample.toDouble()
            filterTwo = sample.toDouble()
        } else {
            filterOne += lowPassAlpha * (sample - filterOne)
            filterTwo += lowPassAlpha * (filterOne - filterTwo)
        }
        return filterTwo.toFloat()
    }
}
