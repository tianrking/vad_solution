package com.tianrking.vadsolution.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts frame-level VAD decisions into ranges while preserving short pauses. */
public final class SpeechSegmenter {
    private final int minSpeechFrames;
    private final int minSilenceFrames;
    private final int keepSilenceSamples;
    private final List<Frame> frames = new ArrayList<>();
    private boolean finished;

    public SpeechSegmenter(VadConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        int frameMs = config.getFrameDurationMs();
        this.minSpeechFrames = Math.max(1, ceilDiv(config.getMinSpeechMs(), frameMs));
        this.minSilenceFrames = Math.max(1, ceilDiv(config.getMinSilenceMs(), frameMs));
        this.keepSilenceSamples = config.getSampleRate() * config.getKeepSilenceMs() / 1000;
    }

    public void accept(VadFrameResult result, int startSample, int endSample) {
        if (finished) {
            throw new IllegalStateException("segmenter has finished; call reset first");
        }
        if (result == null || startSample < 0 || endSample <= startSample) {
            throw new IllegalArgumentException("invalid frame");
        }
        if (!frames.isEmpty() && startSample != frames.get(frames.size() - 1).endSample) {
            throw new IllegalArgumentException("frames must be contiguous");
        }
        frames.add(new Frame(result.isSpeech(), startSample, endSample));
    }

    public List<SpeechSegment> finish(int totalSamples) {
        if (finished) {
            throw new IllegalStateException("finish can only be called once");
        }
        if (totalSamples < 0 || (!frames.isEmpty() && totalSamples < frames.get(frames.size() - 1).endSample)) {
            throw new IllegalArgumentException("invalid totalSamples");
        }
        finished = true;

        if (frames.isEmpty()) {
            return Collections.emptyList();
        }

        List<SpeechSegment> result = new ArrayList<>();
        int candidateStart = -1;
        int candidateSpeechFrames = 0;
        boolean active = false;
        int activeStart = 0;
        int lastSpeechEnd = 0;
        int silentFrames = 0;

        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.speech) {
                if (!active) {
                    if (candidateStart < 0) {
                        candidateStart = i;
                    }
                    candidateSpeechFrames++;
                    if (candidateSpeechFrames >= minSpeechFrames) {
                        active = true;
                        activeStart = Math.max(0, frames.get(candidateStart).startSample - keepSilenceSamples);
                    }
                }
                if (active) {
                    lastSpeechEnd = frame.endSample;
                    silentFrames = 0;
                }
            } else if (active) {
                silentFrames++;
                if (silentFrames >= minSilenceFrames) {
                    addMerged(result, activeStart, Math.min(totalSamples, lastSpeechEnd + keepSilenceSamples));
                    active = false;
                    candidateStart = -1;
                    candidateSpeechFrames = 0;
                    silentFrames = 0;
                }
            } else {
                candidateStart = -1;
                candidateSpeechFrames = 0;
            }
        }

        if (active) {
            addMerged(result, activeStart, Math.min(totalSamples, lastSpeechEnd + keepSilenceSamples));
        }
        return Collections.unmodifiableList(result);
    }

    public void reset() {
        frames.clear();
        finished = false;
    }

    private static void addMerged(List<SpeechSegment> result, int startSample, int endSample) {
        if (endSample <= startSample) {
            return;
        }
        if (!result.isEmpty()) {
            SpeechSegment previous = result.get(result.size() - 1);
            if (startSample <= previous.getEndSample()) {
                result.set(result.size() - 1,
                        new SpeechSegment(previous.getStartSample(), Math.max(previous.getEndSample(), endSample)));
                return;
            }
        }
        result.add(new SpeechSegment(startSample, endSample));
    }

    private static int ceilDiv(int value, int divisor) {
        if (value <= 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    private static final class Frame {
        private final boolean speech;
        private final int startSample;
        private final int endSample;

        private Frame(boolean speech, int startSample, int endSample) {
            this.speech = speech;
            this.startSample = startSample;
            this.endSample = endSample;
        }
    }
}
