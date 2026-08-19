package com.tianrking.vadsolution.sdk;

/** A half-open range of input PCM samples containing speech. */
public final class SpeechSegment {
    private final int startSample;
    private final int endSample;

    public SpeechSegment(int startSample, int endSample) {
        if (startSample < 0 || endSample < startSample) {
            throw new IllegalArgumentException("invalid sample range");
        }
        this.startSample = startSample;
        this.endSample = endSample;
    }

    public int getStartSample() {
        return startSample;
    }

    public int getEndSample() {
        return endSample;
    }

    public int getLengthSamples() {
        return endSample - startSample;
    }

    public long getStartMs(int sampleRate) {
        return Math.round(startSample * 1000.0 / sampleRate);
    }

    public long getEndMs(int sampleRate) {
        return Math.round(endSample * 1000.0 / sampleRate);
    }

    @Override
    public String toString() {
        return "SpeechSegment{" + startSample + ".." + endSample + "}";
    }
}
