package com.tianrking.vadsolution.sdk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of one local Android recording session. */
public final class LocalVadRecording {
    private final File pcmFile;
    private final int sampleRate;
    private final List<SpeechSegment> speechSegments;

    public LocalVadRecording(File pcmFile, int sampleRate, List<SpeechSegment> speechSegments) {
        if (pcmFile == null || sampleRate <= 0 || speechSegments == null) {
            throw new IllegalArgumentException("pcmFile, sampleRate and speechSegments are required");
        }
        this.pcmFile = pcmFile;
        this.sampleRate = sampleRate;
        this.speechSegments = Collections.unmodifiableList(new ArrayList<>(speechSegments));
    }

    public File getPcmFile() {
        return pcmFile;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public List<SpeechSegment> getSpeechSegments() {
        return speechSegments;
    }
}
