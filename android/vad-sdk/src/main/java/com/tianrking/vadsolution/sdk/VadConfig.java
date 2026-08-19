package com.tianrking.vadsolution.sdk;

/** Immutable configuration for the local PCM VAD pipeline. */
public final class VadConfig {
    private final int sampleRate;
    private final int frameDurationMs;
    private final double absoluteThresholdDb;
    private final double relativeThresholdDb;
    private final double noiseAdaptation;
    private final int minSpeechMs;
    private final int minSilenceMs;
    private final int keepSilenceMs;

    private VadConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.frameDurationMs = builder.frameDurationMs;
        this.absoluteThresholdDb = builder.absoluteThresholdDb;
        this.relativeThresholdDb = builder.relativeThresholdDb;
        this.noiseAdaptation = builder.noiseAdaptation;
        this.minSpeechMs = builder.minSpeechMs;
        this.minSilenceMs = builder.minSilenceMs;
        this.keepSilenceMs = builder.keepSilenceMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getFrameDurationMs() {
        return frameDurationMs;
    }

    public int getFrameSamples() {
        return sampleRate * frameDurationMs / 1000;
    }

    public double getAbsoluteThresholdDb() {
        return absoluteThresholdDb;
    }

    public double getRelativeThresholdDb() {
        return relativeThresholdDb;
    }

    public double getNoiseAdaptation() {
        return noiseAdaptation;
    }

    public int getMinSpeechMs() {
        return minSpeechMs;
    }

    public int getMinSilenceMs() {
        return minSilenceMs;
    }

    public int getKeepSilenceMs() {
        return keepSilenceMs;
    }

    public static final class Builder {
        private int sampleRate = 16_000;
        private int frameDurationMs = 20;
        private double absoluteThresholdDb = -48.0;
        private double relativeThresholdDb = 8.0;
        private double noiseAdaptation = 0.05;
        private int minSpeechMs = 80;
        private int minSilenceMs = 700;
        private int keepSilenceMs = 250;

        public Builder setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder setFrameDurationMs(int frameDurationMs) {
            this.frameDurationMs = frameDurationMs;
            return this;
        }

        public Builder setAbsoluteThresholdDb(double thresholdDb) {
            this.absoluteThresholdDb = thresholdDb;
            return this;
        }

        public Builder setRelativeThresholdDb(double thresholdDb) {
            this.relativeThresholdDb = thresholdDb;
            return this;
        }

        public Builder setNoiseAdaptation(double adaptation) {
            this.noiseAdaptation = adaptation;
            return this;
        }

        public Builder setMinSpeechMs(int minSpeechMs) {
            this.minSpeechMs = minSpeechMs;
            return this;
        }

        public Builder setMinSilenceMs(int minSilenceMs) {
            this.minSilenceMs = minSilenceMs;
            return this;
        }

        public Builder setKeepSilenceMs(int keepSilenceMs) {
            this.keepSilenceMs = keepSilenceMs;
            return this;
        }

        public VadConfig build() {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be positive");
            }
            if (frameDurationMs <= 0 || frameDurationMs > 1000) {
                throw new IllegalArgumentException("frameDurationMs must be between 1 and 1000");
            }
            if ((long) sampleRate * frameDurationMs % 1000 != 0) {
                throw new IllegalArgumentException("sampleRate and frameDurationMs must produce an integer frame size");
            }
            if (absoluteThresholdDb > 0 || absoluteThresholdDb < -120) {
                throw new IllegalArgumentException("absoluteThresholdDb must be between -120 and 0");
            }
            if (relativeThresholdDb < 0 || relativeThresholdDb > 60) {
                throw new IllegalArgumentException("relativeThresholdDb must be between 0 and 60");
            }
            if (noiseAdaptation <= 0 || noiseAdaptation > 1) {
                throw new IllegalArgumentException("noiseAdaptation must be in (0, 1]");
            }
            if (minSpeechMs < 0 || minSilenceMs < 0 || keepSilenceMs < 0) {
                throw new IllegalArgumentException("timing values cannot be negative");
            }
            return new VadConfig(this);
        }

        private int getFrameSamples() {
            return sampleRate * frameDurationMs / 1000;
        }
    }
}
