package com.tianrking.vadsolution.sdk;

/**
 * Pluggable frame-level VAD backend.
 *
 * Implementations must accept exactly getFrameSamples() PCM 16-bit mono samples
 * for every call. The interface is deliberately Java-compatible so native,
 * ONNX, Kotlin, and Java providers can share the same segmenter.
 */
public interface VadEngine {
    int getSampleRate();

    int getFrameSamples();

    VadFrameResult process(short[] pcm, int offset, int length);

    void reset();
}
