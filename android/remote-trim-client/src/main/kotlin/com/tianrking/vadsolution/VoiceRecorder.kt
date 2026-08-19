package com.tianrking.vadsolution

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** A small UI-agnostic M4A/AAC recording session. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    @Synchronized
    fun start(): File {
        check(recorder == null) { "A recording is already active" }

        val file = File.createTempFile("recording-", ".m4a", context.cacheDir)
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (error: Throwable) {
            newRecorder.release()
            file.delete()
            throw error
        }

        recorder = newRecorder
        currentFile = file
        return file
    }

    @Synchronized
    fun stop(): File {
        val activeRecorder = recorder ?: error("No active recording")
        val file = currentFile ?: error("Recording file is missing")

        try {
            activeRecorder.stop()
            return file
        } catch (error: RuntimeException) {
            file.delete()
            throw error
        } finally {
            activeRecorder.release()
            recorder = null
            currentFile = null
        }
    }

    @Synchronized
    fun cancel() {
        recorder?.release()
        recorder = null
        currentFile?.delete()
        currentFile = null
    }
}
