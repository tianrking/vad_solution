package com.tianrking.vadsolution.sdk;

import java.io.File;

/** Entry points for the dependency-free local SDK. */
public final class VadSdk {
    private VadSdk() {
    }

    public static PcmVadProcessor createDefault(VadConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        return new PcmVadProcessor(new EnergyVadEngine(config), config);
    }

    public static AndroidVadRecorder createRecorder(VadConfig config, File pcmOutputFile) {
        return new AndroidVadRecorder(config, pcmOutputFile);
    }
}
