package com.tianrking.vadsolution.sdk;

import java.util.List;

/** Streams arbitrary PCM chunks into a frame VAD and returns speech ranges on finish. */
public final class PcmVadProcessor {
    private final VadEngine engine;
    private final SpeechSegmenter segmenter;
    private final short[] frame;
    private int frameFill;
    private int processedSamples;
    private boolean finished;

    public PcmVadProcessor(VadEngine engine, VadConfig config) {
        if (engine == null || config == null) {
            throw new IllegalArgumentException("engine and config are required");
        }
        if (engine.getSampleRate() != config.getSampleRate()
                || engine.getFrameSamples() != config.getFrameSamples()) {
            throw new IllegalArgumentException("engine and config frame formats differ");
        }
        this.engine = engine;
        this.segmenter = new SpeechSegmenter(config);
        this.frame = new short[engine.getFrameSamples()];
    }

    public void accept(short[] pcm) {
        if (pcm == null) {
            throw new IllegalArgumentException("pcm cannot be null");
        }
        accept(pcm, 0, pcm.length);
    }

    public void accept(short[] pcm, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("processor has finished; call reset first");
        }
        if (pcm == null || offset < 0 || length < 0 || offset > pcm.length - length) {
            throw new IllegalArgumentException("invalid PCM range");
        }
        int cursor = offset;
        int remaining = length;
        while (remaining > 0) {
            int copy = Math.min(remaining, frame.length - frameFill);
            System.arraycopy(pcm, cursor, frame, frameFill, copy);
            frameFill += copy;
            cursor += copy;
            remaining -= copy;
            if (frameFill == frame.length) {
                processFrame(frame.length);
                frameFill = 0;
            }
        }
    }

    public List<SpeechSegment> finish() {
        if (finished) {
            throw new IllegalStateException("finish can only be called once");
        }
        if (frameFill > 0) {
            for (int i = frameFill; i < frame.length; i++) {
                frame[i] = 0;
            }
            processFrame(frameFill);
            frameFill = 0;
        }
        finished = true;
        return segmenter.finish(processedSamples);
    }

    public void reset() {
        frameFill = 0;
        processedSamples = 0;
        finished = false;
        engine.reset();
        segmenter.reset();
    }

    public int getSampleRate() {
        return engine.getSampleRate();
    }

    private void processFrame(int validSamples) {
        VadFrameResult result = engine.process(frame, 0, frame.length);
        int startSample = processedSamples;
        processedSamples += validSamples;
        segmenter.accept(result, startSample, processedSamples);
    }
}
