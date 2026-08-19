package com.vadcut.android.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Drops PCM frames outside [ranges] and applies short linear fades at edit boundaries. */
@UnstableApi
internal class RangeAudioProcessor(
    private val ranges: List<TimeRangeUs>,
    private val fadeDurationUs: Long,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var fadeFrames = 0L
    private var inputFramePosition = 0L
    private var rangeIndex = 0
    private var frameRanges: List<LongRange> = emptyList()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException("VadCut requires 16-bit PCM:", inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        fadeFrames = fadeDurationUs * sampleRate / 1_000_000L
        frameRanges = ranges.mapNotNull { range ->
            val start = range.startUs * sampleRate / 1_000_000L
            val endExclusive = ceilFrames(range.endUs, sampleRate)
            if (endExclusive > start) start until endExclusive else null
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        super.onFlush(streamMetadata)
        inputFramePosition = streamMetadata.positionOffsetUs.coerceAtLeast(0L) * sampleRate / 1_000_000L
        rangeIndex = frameRanges.indexOfFirst { inputFramePosition <= it.last }.let { if (it < 0) frameRanges.size else it }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= bytesPerFrame) {
            while (rangeIndex < frameRanges.size && inputFramePosition > frameRanges[rangeIndex].last) {
                rangeIndex += 1
            }
            if (rangeIndex >= frameRanges.size) {
                val skippedFrames = inputBuffer.remaining() / bytesPerFrame
                inputBuffer.position(inputBuffer.position() + skippedFrames * bytesPerFrame)
                inputFramePosition += skippedFrames
                break
            }

            val range = frameRanges[rangeIndex]
            if (inputFramePosition < range.first) {
                val availableFrames = inputBuffer.remaining() / bytesPerFrame
                val skippedFrames = minOf(availableFrames.toLong(), range.first - inputFramePosition).toInt()
                inputBuffer.position(inputBuffer.position() + skippedFrames * bytesPerFrame)
                inputFramePosition += skippedFrames
                continue
            }

            val availableFrames = inputBuffer.remaining() / bytesPerFrame
            val framesInRange = minOf(availableFrames.toLong(), range.last - inputFramePosition + 1L).toInt()
            val fadeInEnd = range.first + fadeFrames
            val fadeOutStart = range.last - fadeFrames + 1L
            val bulkEnd = minOf(inputFramePosition + framesInRange, fadeOutStart)

            if (fadeFrames == 0L || (inputFramePosition >= fadeInEnd && bulkEnd > inputFramePosition)) {
                val bulkFrames = if (fadeFrames == 0L) {
                    framesInRange
                } else {
                    (bulkEnd - inputFramePosition).toInt()
                }
                copyBytes(inputBuffer, outputBuffer, bulkFrames * bytesPerFrame)
                inputFramePosition += bulkFrames
                if (bulkFrames < framesInRange) continue
            } else {
                applyFadeToOneFrame(inputBuffer, outputBuffer, range)
                inputFramePosition += 1L
            }
        }

        if (inputBuffer.hasRemaining()) {
            // Media3 should only provide complete PCM frames. Consume any malformed tail safely.
            inputBuffer.position(inputBuffer.limit())
        }
        outputBuffer.flip()
    }

    private fun applyFadeToOneFrame(input: ByteBuffer, output: ByteBuffer, range: LongRange) {
        val offset = inputFramePosition - range.first
        val reverseOffset = range.last - inputFramePosition
        val fadeInGain = if (fadeFrames <= 1L) 1f else (offset.toFloat() / (fadeFrames - 1L)).coerceIn(0f, 1f)
        val fadeOutGain = if (fadeFrames <= 1L) 1f else (reverseOffset.toFloat() / (fadeFrames - 1L)).coerceIn(0f, 1f)
        val gain = minOf(fadeInGain, fadeOutGain)
        repeat(channelCount) {
            val sample = input.short.toInt()
            output.putShort((sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun copyBytes(input: ByteBuffer, output: ByteBuffer, byteCount: Int) {
        val originalLimit = input.limit()
        input.limit(input.position() + byteCount)
        output.put(input)
        input.limit(originalLimit)
    }

    private fun ceilFrames(timeUs: Long, rate: Int): Long =
        (timeUs * rate + 999_999L) / 1_000_000L
}
