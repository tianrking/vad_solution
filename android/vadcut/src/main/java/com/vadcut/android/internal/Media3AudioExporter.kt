package com.vadcut.android.internal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ExportStats(
    val approximateDurationMs: Long,
    val fileSizeBytes: Long,
)

@UnstableApi
internal class Media3AudioExporter(private val context: Context) {
    suspend fun export(
        inputUri: Uri,
        outputFile: File,
        keptRanges: List<TimeRangeUs>,
        fadeDurationUs: Long,
        onProgress: (Int) -> Unit,
    ): ExportStats = withContext(Dispatchers.Main.immediate) {
        if (outputFile.exists() && !outputFile.delete()) {
            throw TrimException(TrimErrorCode.EXPORT_FAILED, "Unable to prepare temporary output file")
        }

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val processor = RangeAudioProcessor(keptRanges, fadeDurationUs)
            lateinit var transformer: Transformer
            lateinit var progressPoller: Runnable

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressPoller)
                    if (continuation.isActive) {
                        onProgress(100)
                        continuation.resume(
                            ExportStats(
                                approximateDurationMs = exportResult.approximateDurationMs,
                                fileSizeBytes = exportResult.fileSizeBytes,
                            ),
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacks(progressPoller)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TrimException(TrimErrorCode.EXPORT_FAILED, "Media3 audio export failed", exportException),
                        )
                    }
                }
            }

            try {
                transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // The SDK writes a complete local artifact. Avoid Media3's default reserved
                    // fast-start space, which adds roughly 400 KB to every short M4A file.
                    .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
                    .addListener(listener)
                    .build()
                val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(processor), emptyList()))
                    .build()

                val progressHolder = ProgressHolder()
                progressPoller = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 100))
                        }
                        handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                    }
                }

                continuation.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(progressPoller)
                        runCatching { transformer.cancel() }
                        runCatching { outputFile.delete() }
                    }
                }
                transformer.start(editedItem, outputFile.absolutePath)
                handler.post(progressPoller)
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (error is TrimException) error else {
                            TrimException(TrimErrorCode.EXPORT_FAILED, "Unable to start Media3 export", error)
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 250L
    }
}
