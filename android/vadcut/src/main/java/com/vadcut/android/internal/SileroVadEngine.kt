package com.vadcut.android.internal

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vadcut.android.TrimErrorCode
import com.vadcut.android.TrimException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

internal class SileroVadEngine(
    context: Context,
    verifyIntegrity: Boolean,
) : ActivityDetector {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputBuffer: FloatBuffer = directFloatBuffer(INPUT_SAMPLE_COUNT)
    private val stateBuffer: FloatBuffer = directFloatBuffer(STATE_SIZE)
    private val sampleRateBuffer: LongBuffer = ByteBuffer.allocateDirect(Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()
    private val inputTensor: OnnxTensor
    private val stateTensor: OnnxTensor
    private val sampleRateTensor: OnnxTensor
    private val recurrentState = FloatArray(STATE_SIZE)
    private val contextWindow = FloatArray(CONTEXT_SAMPLE_COUNT)

    init {
        val model = try {
            context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.MODEL_LOAD_FAILED, "Unable to load the bundled Silero VAD model", error)
        }
        if (verifyIntegrity) {
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(model)
                .joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                throw TrimException(
                    TrimErrorCode.MODEL_INTEGRITY_FAILED,
                    "Bundled Silero VAD model failed its SHA-256 integrity check",
                )
            }
        }

        session = try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(model, options)
            }
        } catch (error: Throwable) {
            throw TrimException(TrimErrorCode.MODEL_LOAD_FAILED, "Unable to initialize ONNX Runtime", error)
        }

        inputTensor = OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, INPUT_SAMPLE_COUNT.toLong()))
        stateTensor = OnnxTensor.createTensor(environment, stateBuffer, longArrayOf(2, 1, 128))
        sampleRateBuffer.put(0, SAMPLE_RATE.toLong())
        sampleRateTensor = OnnxTensor.createTensor(environment, sampleRateBuffer, longArrayOf(1))
    }

    override fun score(samples: FloatArray, validSampleCount: Int): Float {
        inputBuffer.clear()
        inputBuffer.put(contextWindow)
        inputBuffer.put(samples, 0, FRAME_SAMPLE_COUNT)
        inputBuffer.rewind()

        stateBuffer.clear()
        stateBuffer.put(recurrentState)
        stateBuffer.rewind()

        val probability = session.run(
            mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to sampleRateTensor,
            ),
        ).use { result ->
            val output = result.get("output").orElseThrow() as OnnxTensor
            val nextState = result.get("stateN").orElseThrow() as OnnxTensor
            val nextStateBuffer = nextState.floatBuffer
            nextStateBuffer.get(recurrentState, 0, STATE_SIZE)
            output.floatBuffer.get(0)
        }

        System.arraycopy(
            samples,
            FRAME_SAMPLE_COUNT - CONTEXT_SAMPLE_COUNT,
            contextWindow,
            0,
            CONTEXT_SAMPLE_COUNT,
        )
        return probability.coerceIn(0f, 1f)
    }

    override fun close() {
        sampleRateTensor.close()
        stateTensor.close()
        inputTensor.close()
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLE_COUNT = 512
        private const val CONTEXT_SAMPLE_COUNT = 64
        private const val INPUT_SAMPLE_COUNT = FRAME_SAMPLE_COUNT + CONTEXT_SAMPLE_COUNT
        private const val STATE_SIZE = 256
        private const val MODEL_ASSET_PATH = "vadcut/silero_vad.onnx"
        private const val MODEL_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"

        private fun directFloatBuffer(capacity: Int): FloatBuffer =
            ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
    }
}
