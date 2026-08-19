package com.vadcut.android.internal

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.vadcut.android.TrimConfig
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException
import com.vadcut.android.TrimMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MediaCodecAudioAnalyzer(private val context: Context) {
    suspend fun analyze(
        inputUri: Uri,
        config: TrimConfig,
        onProgress: suspend (processedUs: Long, totalUs: Long) -> Unit,
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        val detector = when (config.mode) {
            TrimMode.SPEECH -> SileroVadEngine(context, config.verifyModelIntegrity)
            TrimMode.NON_SILENCE -> EnergyActivityDetector(config.energyThresholdDb)
        }

        try {
            try {
                extractor.setDataSource(context, inputUri, null)
            } catch (error: Throwable) {
                throw TrimException(TrimErrorCode.INPUT_OPEN_FAILED, "Unable to open input URI: $inputUri", error)
            }

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                throw TrimException(TrimErrorCode.NO_AUDIO_TRACK, "The input contains no decodable audio track")
            }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw TrimException(TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT, "Audio MIME type is missing")
            val declaredDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val durationWasKnown = declaredDurationUs > 0L

            decoder = try {
                MediaCodec.createDecoderByType(mimeType)
            } catch (error: Throwable) {
                throw TrimException(
                    TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                    "No Android MediaCodec decoder is available for $mimeType",
                    error,
                )
            }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            decoderStarted = true

            val planner = ActivitySegmentPlanner(config)
            val frames = VadFrameCollector(detector, planner)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var outputChannelCount = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingMonoResampler? = null
            var decodedSourceFrames = 0L
            var lastProgressAtMs = 0L

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()

                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw TrimException(TrimErrorCode.ANALYSIS_FAILED, "Decoder returned a null input buffer")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        val newRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputSampleRate
                        val newChannels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannelCount
                        val newEncoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        if (resampler != null && newRate != outputSampleRate) {
                            throw TrimException(
                                TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                                "Audio sample rate changed during decoding ($outputSampleRate to $newRate Hz)",
                            )
                        }
                        outputSampleRate = newRate
                        outputChannelCount = newChannels
                        pcmEncoding = newEncoding
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            if (outputSampleRate <= 0 || outputChannelCount <= 0) {
                                throw TrimException(
                                    TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                                    "Decoder did not report a valid PCM sample rate and channel count",
                                )
                            }
                            val currentResampler = resampler ?: StreamingMonoResampler(
                                inputSampleRate = outputSampleRate,
                                outputSampleRate = SileroVadEngine.SAMPLE_RATE,
                                output = frames::accept,
                            ).also { resampler = it }
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: throw TrimException(TrimErrorCode.ANALYSIS_FAILED, "Decoder returned a null output buffer")
                            val pcm = outputBuffer.duplicate().order(ByteOrder.nativeOrder())
                            pcm.position(bufferInfo.offset)
                            pcm.limit(bufferInfo.offset + bufferInfo.size)
                            decodedSourceFrames += consumePcm(
                                buffer = pcm,
                                channelCount = outputChannelCount,
                                encoding = pcmEncoding,
                                resampler = currentResampler,
                            )
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAtMs >= PROGRESS_INTERVAL_MS || outputEnded) {
                            lastProgressAtMs = now
                            val processedUs = if (bufferInfo.presentationTimeUs > 0L) {
                                bufferInfo.presentationTimeUs
                            } else if (outputSampleRate > 0) {
                                decodedSourceFrames * 1_000_000L / outputSampleRate
                            } else {
                                0L
                            }
                            onProgress(processedUs.coerceAtLeast(0L), declaredDurationUs)
                        }
                    }
                }
            }

            if (resampler == null || decodedSourceFrames == 0L || outputSampleRate <= 0) {
                throw TrimException(TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT, "The decoder produced no PCM audio")
            }
            val decodedDurationUs = decodedSourceFrames * 1_000_000L / outputSampleRate
            val durationUs = maxOf(decodedDurationUs, frames.outputDurationUs).coerceAtLeast(1L)
            AnalysisResult(
                durationUs = durationUs,
                keptRanges = frames.finish(durationUs),
                durationWasKnown = durationWasKnown,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TrimException) {
            throw error
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.ANALYSIS_FAILED, "Audio analysis failed", error)
        } finally {
            runCatching { if (decoderStarted) decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { detector.close() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        channelCount: Int,
        encoding: Int,
        resampler: StreamingMonoResampler,
    ): Long {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> throw TrimException(
                TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT,
                "Unsupported decoder PCM encoding: $encoding",
            )
        }
        val bytesPerFrame = bytesPerSample * channelCount
        if (buffer.remaining() % bytesPerFrame != 0) {
            throw TrimException(TrimErrorCode.UNSUPPORTED_AUDIO_FORMAT, "Decoder produced unaligned PCM data")
        }
        val frameCount = buffer.remaining() / bytesPerFrame
        repeat(frameCount) {
            var mono = 0f
            repeat(channelCount) {
                mono += when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    else -> 0f
                }
            }
            resampler.accept(mono / channelCount)
        }
        return frameCount.toLong()
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private class VadFrameCollector(
        private val detector: ActivityDetector,
        private val planner: ActivitySegmentPlanner,
    ) {
        private val frame = FloatArray(SileroVadEngine.FRAME_SAMPLE_COUNT)
        private var frameSampleCount = 0
        private var totalOutputSamples = 0L

        val outputDurationUs: Long
            get() = totalOutputSamples * 1_000_000L / SileroVadEngine.SAMPLE_RATE

        fun accept(sample: Float) {
            frame[frameSampleCount++] = sample
            totalOutputSamples += 1L
            if (frameSampleCount == frame.size) {
                analyzeFrame(frame.size)
                frameSampleCount = 0
            }
        }

        fun finish(durationUs: Long): List<TimeRangeUs> {
            if (frameSampleCount > 0) {
                frame.fill(0f, frameSampleCount, frame.size)
                analyzeFrame(frameSampleCount)
                frameSampleCount = 0
            }
            return planner.finish(durationUs)
        }

        private fun analyzeFrame(validSamples: Int) {
            val frameEndUs = outputDurationUs
            val frameStartUs = ((totalOutputSamples - validSamples) * 1_000_000L / SileroVadEngine.SAMPLE_RATE)
                .coerceAtLeast(0L)
            planner.accept(detector.score(frame, validSamples), frameStartUs, frameEndUs)
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
