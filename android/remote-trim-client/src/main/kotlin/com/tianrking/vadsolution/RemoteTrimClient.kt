package com.tianrking.vadsolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException

data class TrimOptions(
    val outputFormat: String = "m4a",
    val frameMs: Int = 20,
    val aggressiveness: Int = 2,
    val minSilenceMs: Int = 800,
    val keepSilenceMs: Int = 250,
    val paddingMs: Int = 80,
    val minSpeechMs: Int = 160,
)

data class TrimResult(
    val outputFile: File,
    val originalDurationSeconds: Double?,
    val outputDurationSeconds: Double?,
    val detectedSpeechRanges: Int?,
)

class RemoteTrimClient(
    baseUrl: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val endpoint = baseUrl.trimEnd('/')
        .toHttpUrl()
        .newBuilder()
        .addPathSegments("v1/audio/remove-long-silence")
        .build()

    suspend fun trim(
        input: File,
        output: File,
        options: TrimOptions = TrimOptions(),
    ): TrimResult = withContext(Dispatchers.IO) {
        require(input.isFile) { "Input audio does not exist: ${input.absolutePath}" }
        require(options.outputFormat == "m4a" || options.outputFormat == "wav") {
            "outputFormat must be m4a or wav"
        }

        val inputType = when (input.extension.lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            else -> "application/octet-stream"
        }.toMediaType()

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", input.name, input.asRequestBody(inputType))
            .addFormDataPart("output_format", options.outputFormat)
            .addFormDataPart("frame_ms", options.frameMs.toString())
            .addFormDataPart("aggressiveness", options.aggressiveness.toString())
            .addFormDataPart("min_silence_ms", options.minSilenceMs.toString())
            .addFormDataPart("keep_silence_ms", options.keepSilenceMs.toString())
            .addFormDataPart("padding_ms", options.paddingMs.toString())
            .addFormDataPart("min_speech_ms", options.minSpeechMs.toString())
            .build()

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(form)
        if (apiKey.isNotBlank()) {
            requestBuilder.header("X-API-Key", apiKey)
        }

        val partial = File(output.parentFile ?: error("Output has no parent"), "${output.name}.part")
        partial.parentFile?.mkdirs()
        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(500).orEmpty()
                    throw IOException("Audio trim failed: HTTP ${response.code} ${detail}".trim())
                }
                val body = response.body ?: throw IOException("Audio trim returned an empty body")
                body.byteStream().use { inputStream ->
                    partial.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (output.exists() && !output.delete()) {
                    throw IOException("Cannot replace output file: ${output.absolutePath}")
                }
                if (!partial.renameTo(output)) {
                    throw IOException("Cannot finalize output file: ${output.absolutePath}")
                }

                return@withContext TrimResult(
                    outputFile = output,
                    originalDurationSeconds = response.header("X-Original-Duration-Seconds")?.toDoubleOrNull(),
                    outputDurationSeconds = response.header("X-Output-Duration-Seconds")?.toDoubleOrNull(),
                    detectedSpeechRanges = response.header("X-Detected-Speech-Ranges")?.toIntOrNull(),
                )
            }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }
}
