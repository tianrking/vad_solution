package com.vadcut.android.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class RangeAudioProcessorTest {
    @Test
    fun dropsFramesOutsideKeptRange() {
        val processor = RangeAudioProcessor(listOf(TimeRangeUs(2_000L, 5_000L)), fadeDurationUs = 0L)
        processor.configure(AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)

        val input = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder())
        repeat(10) { input.putShort(it.toShort()) }
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder())
        val samples = ShortArray(output.remaining() / Short.SIZE_BYTES)
        output.asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(2, 3, 4), samples)
    }

    @Test
    fun fadesBothEditBoundaries() {
        val processor = RangeAudioProcessor(listOf(TimeRangeUs(0L, 5_000L)), fadeDurationUs = 2_000L)
        processor.configure(AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)

        val input = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder())
        repeat(5) { input.putShort(1_000) }
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder())
        val samples = ShortArray(output.remaining() / Short.SIZE_BYTES)
        output.asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(0, 1_000, 1_000, 1_000, 0), samples)
    }
}
