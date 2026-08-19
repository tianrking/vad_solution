package com.vadcut.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StreamingMonoResamplerTest {
    @Test
    fun downsamplingOneSecondProducesExpectedFrameCount() {
        val output = mutableListOf<Float>()
        val resampler = StreamingMonoResampler(48_000, 16_000, output::add)
        repeat(48_000) { index ->
            resampler.accept(sin(2.0 * Math.PI * 440.0 * index / 48_000.0).toFloat())
        }
        assertEquals(16_000, output.size)
        assertTrue(output.all { it in -1f..1f })
    }
}
