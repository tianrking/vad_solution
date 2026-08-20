package com.vadcut.android.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import com.vadcut.android.AudioRange
import com.vadcut.android.NoSpeechPolicy
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException
import com.vadcut.android.TrimPhase
import com.vadcut.android.TrimProgress
import com.vadcut.android.TrimRequest
import com.vadcut.android.TrimResult
import com.vadcut.android.TrimWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

@AndroidxOptIn(UnstableApi::class)
internal class TrimCoordinator(private val context: Context) {
    private val analyzer = MediaCodecAudioAnalyzer(context)
    private val exporter = Media3AudioExporter(context)

    suspend fun trim(
        request: TrimRequest,
        onProgress: (TrimProgress) -> Unit,
    ): TrimResult {
        validate(request)
        emitProgress(onProgress, TrimPhase.ANALYZING, 0, 0L, 0L)

        val analysisProgress: suspend (Long, Long) -> Unit = { processedUs, totalUs ->
            val analysisPercent = if (totalUs > 0L) {
                ((processedUs.coerceAtMost(totalUs) * ANALYSIS_WEIGHT) / totalUs).toInt()
            } else {
                0
            }
            emitProgress(
                onProgress,
                TrimPhase.ANALYZING,
                analysisPercent,
                processedUs / 1_000L,
                totalUs / 1_000L,
            )
        }
        val analysis = if (request.manualTrimPlan == null) {
            analyzer.analyze(request.inputUri, request.config, analysisProgress)
        } else {
            analyzer.analyzeDuration(request.inputUri, analysisProgress)
        }

        val warnings = mutableListOf<TrimWarning>()
        if (!analysis.durationWasKnown) warnings += TrimWarning.INPUT_DURATION_UNKNOWN
        val keptRanges = request.manualTrimPlan?.let { manualPlan ->
            ManualRangePlanner.resolveKeptRanges(manualPlan, analysis.durationUs)
        } ?: run {
            if (analysis.keptRanges.isEmpty()) {
                when (request.config.noSpeechPolicy) {
                    NoSpeechPolicy.FAIL -> throw TrimException(
                        TrimErrorCode.NO_SPEECH_DETECTED,
                        "No requested audio activity was detected",
                    )

                    NoSpeechPolicy.KEEP_ORIGINAL -> {
                        warnings += TrimWarning.NO_ACTIVITY_DETECTED_KEPT_ORIGINAL
                        listOf(TimeRangeUs(0L, analysis.durationUs))
                    }
                }
            } else {
                analysis.keptRanges
            }
        }

        emitProgress(
            onProgress,
            TrimPhase.EXPORTING,
            ANALYSIS_WEIGHT,
            0L,
            analysis.durationUs / 1_000L,
        )
        val temporaryOutput = try {
            File.createTempFile("vadcut-", ".m4a", context.cacheDir)
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.EXPORT_FAILED, "Unable to create a temporary output file", error)
        }
        try {
            exporter.export(
                inputUri = request.inputUri,
                outputFile = temporaryOutput,
                keptRanges = keptRanges,
                fadeDurationUs = request.config.fadeDurationMs * 1_000L,
            ) { exportPercent ->
                val overall = ANALYSIS_WEIGHT + exportPercent * EXPORT_WEIGHT / 100
                onProgress(
                    TrimProgress(
                        phase = TrimPhase.EXPORTING,
                        percent = overall,
                        processedDurationMs = analysis.durationUs / 1_000L * exportPercent / 100,
                        totalDurationMs = analysis.durationUs / 1_000L,
                    ),
                )
            }

            emitProgress(
                onProgress,
                TrimPhase.WRITING_OUTPUT,
                ANALYSIS_WEIGHT + EXPORT_WEIGHT,
                0L,
                analysis.durationUs / 1_000L,
            )
            copyToUri(temporaryOutput, request.outputUri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TrimException) {
            throw error
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.EXPORT_FAILED, "Unable to create trimmed audio", error)
        } finally {
            runCatching { temporaryOutput.delete() }
        }

        val keptDurationUs = keptRanges.sumOf { it.durationUs }
        val removedRanges = complement(keptRanges, analysis.durationUs)
        val result = TrimResult(
            outputUri = request.outputUri,
            inputDurationMs = usToRoundedMs(analysis.durationUs),
            outputDurationMs = usToRoundedMs(keptDurationUs),
            removedDurationMs = usToRoundedMs((analysis.durationUs - keptDurationUs).coerceAtLeast(0L)),
            keptRanges = keptRanges.map(::toPublicRange),
            removedRanges = removedRanges.map(::toPublicRange),
            warnings = warnings.toList(),
        )
        emitProgress(
            onProgress,
            TrimPhase.COMPLETED,
            100,
            result.inputDurationMs,
            result.inputDurationMs,
        )
        return result
    }

    private suspend fun emitProgress(
        callback: (TrimProgress) -> Unit,
        phase: TrimPhase,
        percent: Int,
        processedMs: Long,
        totalMs: Long,
    ) = withContext(Dispatchers.Main.immediate) {
        callback(TrimProgress(phase, percent.coerceIn(0, 100), processedMs, totalMs))
    }

    private suspend fun copyToUri(source: File, outputUri: Uri) = withContext(Dispatchers.IO) {
        try {
            val destination = when (outputUri.scheme) {
                ContentResolver.SCHEME_FILE -> {
                    val path = outputUri.path
                        ?: throw TrimException(TrimErrorCode.INVALID_REQUEST, "Output file URI has no path")
                    File(path).outputStream()
                }

                ContentResolver.SCHEME_CONTENT -> context.contentResolver.openOutputStream(outputUri, "w")
                    ?: throw TrimException(TrimErrorCode.OUTPUT_WRITE_FAILED, "Unable to open output URI")

                else -> throw TrimException(
                    TrimErrorCode.INVALID_REQUEST,
                    "Output URI must use content:// or file://",
                )
            }
            destination.buffered().use { output ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
        } catch (error: TrimException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.OUTPUT_WRITE_FAILED, "Unable to write output URI", error)
        }
    }

    private fun validate(request: TrimRequest) {
        if (request.inputUri == Uri.EMPTY || request.outputUri == Uri.EMPTY) {
            throw TrimException(TrimErrorCode.INVALID_REQUEST, "Input and output URIs are required")
        }
        if (request.inputUri == request.outputUri) {
            throw TrimException(TrimErrorCode.INVALID_REQUEST, "In-place overwrite is not supported")
        }
    }

    companion object {
        private const val ANALYSIS_WEIGHT = 60
        private const val EXPORT_WEIGHT = 35

        internal fun complement(ranges: List<TimeRangeUs>, durationUs: Long): List<TimeRangeUs> =
            ManualRangePlanner.complement(ranges, durationUs)

        private fun toPublicRange(range: TimeRangeUs): AudioRange =
            AudioRange(usToRoundedMs(range.startUs), usToRoundedMs(range.endUs))

        private fun usToRoundedMs(valueUs: Long): Long = ManualRangePlanner.usToRoundedMs(valueUs)
    }
}
