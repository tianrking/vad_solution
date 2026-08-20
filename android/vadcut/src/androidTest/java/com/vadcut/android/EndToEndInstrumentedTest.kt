package com.vadcut.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EndToEndInstrumentedTest {
    @Test
    fun trimsLeadingAndTrailingSilenceIntoPlayableM4a() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "vadcut-instrumented-input.wav")
        val output = File(context.cacheDir, "vadcut-instrumented-output.m4a")
        instrumentation.context.assets.open("vad-smoke.wav").use { source ->
            input.outputStream().use(source::copyTo)
        }
        output.delete()

        try {
            val request = TrimRequest.Builder(Uri.fromFile(input), Uri.fromFile(output))
                .setConfig(TrimConfig.fromPreset(TrimPreset.VOICE_MEMO))
                .build()
            val result = withTimeout(120_000L) {
                VadCut.with(context).trim(request)
            }

            assertTrue(output.exists())
            assertTrue(output.length() > 1_024L)
            assertTrue(result.removedDurationMs > 3_000L)
            assertTrue(result.outputDurationMs in 6_000L..9_000L)
            assertTrue(result.keptRanges.isNotEmpty())
            assertPlayableAudio(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun manuallyRemovesRequestedOriginalTimelineRanges() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "vadcut-manual-remove-input.wav")
        val output = File(context.cacheDir, "vadcut-manual-remove-output.m4a")
        instrumentation.context.assets.open("vad-smoke.wav").use { source ->
            input.outputStream().use(source::copyTo)
        }
        output.delete()

        try {
            val removed = listOf(AudioRange(1_000L, 3_000L), AudioRange(5_000L, 6_500L))
            val request = TrimRequest.Builder(Uri.fromFile(input), Uri.fromFile(output))
                .setRemovedRanges(removed)
                .build()
            val result = withTimeout(120_000L) {
                VadCut.with(context).trim(request)
            }

            assertEquals(11_478L, result.inputDurationMs)
            assertEquals(3_500L, result.removedDurationMs)
            assertEquals(7_978L, result.outputDurationMs)
            assertEquals(removed, result.removedRanges)
            assertEquals(
                listOf(
                    AudioRange(0L, 1_000L),
                    AudioRange(3_000L, 5_000L),
                    AudioRange(6_500L, 11_478L),
                ),
                result.keptRanges,
            )
            assertTrue(result.warnings.isEmpty())
            assertTrue(output.length() > 1_024L)
            assertPlayableAudio(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun manuallyKeepsOnlyRequestedOriginalTimelineRanges() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "vadcut-manual-keep-input.wav")
        val output = File(context.cacheDir, "vadcut-manual-keep-output.m4a")
        instrumentation.context.assets.open("vad-smoke.wav").use { source ->
            input.outputStream().use(source::copyTo)
        }
        output.delete()

        try {
            val kept = listOf(AudioRange(1_000L, 3_000L), AudioRange(5_000L, 6_500L))
            val request = TrimRequest.Builder(Uri.fromFile(input), Uri.fromFile(output))
                .setKeptRanges(kept)
                .build()
            val result = withTimeout(120_000L) {
                VadCut.with(context).trim(request)
            }

            assertEquals(11_478L, result.inputDurationMs)
            assertEquals(7_978L, result.removedDurationMs)
            assertEquals(3_500L, result.outputDurationMs)
            assertEquals(kept, result.keptRanges)
            assertEquals(
                listOf(
                    AudioRange(0L, 1_000L),
                    AudioRange(3_000L, 5_000L),
                    AudioRange(6_500L, 11_478L),
                ),
                result.removedRanges,
            )
            assertTrue(result.warnings.isEmpty())
            assertTrue(output.length() > 1_024L)
            assertPlayableAudio(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun assertPlayableAudio(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertTrue(extractor.trackCount > 0)
            val audioTracks = (0 until extractor.trackCount).count { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            assertEquals(1, audioTracks)
        } finally {
            extractor.release()
        }
    }
}
