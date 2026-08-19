package com.tianrking.vadsolution.sdk;

/**
 * Small adaptive energy VAD for long-silence trimming.
 *
 * This is intentionally a baseline backend, not a speech recognizer. It keeps
 * the core AAR free from model files and native dependencies.
 */
public final class EnergyVadEngine implements VadEngine {
    private static final double MIN_DB = -120.0;
    private final VadConfig config;
    private final int frameSamples;
    private double noiseFloorDb = -60.0;
    private int observedFrames;

    public EnergyVadEngine(VadConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        this.config = config;
        this.frameSamples = config.getFrameSamples();
    }

    @Override
    public int getSampleRate() {
        return config.getSampleRate();
    }

    @Override
    public int getFrameSamples() {
        return frameSamples;
    }

    @Override
    public VadFrameResult process(short[] pcm, int offset, int length) {
        if (pcm == null) {
            throw new IllegalArgumentException("pcm cannot be null");
        }
        if (offset < 0 || length != frameSamples || offset > pcm.length - length) {
            throw new IllegalArgumentException("one complete frame is required");
        }

        double sumSquares = 0.0;
        for (int i = offset; i < offset + length; i++) {
            double normalized = pcm[i] / 32768.0;
            sumSquares += normalized * normalized;
        }
        double rms = Math.sqrt(sumSquares / length);
        double energyDb = rms <= 0.0 ? MIN_DB : Math.max(MIN_DB, 20.0 * Math.log10(rms));

        double thresholdDb = Math.max(
                config.getAbsoluteThresholdDb(),
                noiseFloorDb + config.getRelativeThresholdDb());
        boolean speech = energyDb >= thresholdDb;

        // A short warm-up lets the detector adapt to a device's microphone
        // floor. During warm-up, adapt faster but keep the absolute threshold.
        double adaptation = observedFrames < 10
                ? Math.max(config.getNoiseAdaptation(), 0.35)
                : config.getNoiseAdaptation();
        if (!speech || observedFrames < 10) {
            noiseFloorDb += (energyDb - noiseFloorDb) * adaptation;
        }
        observedFrames++;
        return new VadFrameResult(speech, energyDb, noiseFloorDb);
    }

    @Override
    public void reset() {
        noiseFloorDb = -60.0;
        observedFrames = 0;
    }
}
