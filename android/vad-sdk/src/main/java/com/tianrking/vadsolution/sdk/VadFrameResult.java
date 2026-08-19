package com.tianrking.vadsolution.sdk;

/** Result for one PCM frame. */
public final class VadFrameResult {
    private final boolean speech;
    private final double energyDb;
    private final double noiseFloorDb;

    public VadFrameResult(boolean speech, double energyDb, double noiseFloorDb) {
        this.speech = speech;
        this.energyDb = energyDb;
        this.noiseFloorDb = noiseFloorDb;
    }

    public boolean isSpeech() {
        return speech;
    }

    public double getEnergyDb() {
        return energyDb;
    }

    public double getNoiseFloorDb() {
        return noiseFloorDb;
    }
}
