package com.tianrking.vadsolution.sdk;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Offline/local recorder that captures mono PCM, runs VAD on the same stream,
 * and stores the original PCM so the caller can export the selected ranges.
 *
 * The host application must request RECORD_AUDIO before calling start().
 */
public final class AndroidVadRecorder implements Closeable {
    private final VadConfig config;
    private final File pcmOutputFile;
    private final Object stateLock = new Object();
    private final PcmVadProcessor processor;
    private AudioRecord audioRecord;
    private Thread worker;
    private volatile boolean running;
    private volatile Throwable workerFailure;
    private LocalVadRecording result;

    public AndroidVadRecorder(VadConfig config, File pcmOutputFile) {
        if (config == null || pcmOutputFile == null) {
            throw new IllegalArgumentException("config and pcmOutputFile are required");
        }
        this.config = config;
        this.pcmOutputFile = pcmOutputFile;
        this.processor = VadSdk.createDefault(config);
    }

    public void start() throws IOException {
        synchronized (stateLock) {
            if (worker != null) {
                throw new IllegalStateException("recorder can only be started once; create a new instance");
            }
            File parent = pcmOutputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IOException("cannot create PCM output directory: " + parent);
            }

            int minBufferBytes = AudioRecord.getMinBufferSize(
                    config.getSampleRate(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferBytes <= 0) {
                throw new IOException("AudioRecord does not support the requested format");
            }
            int bufferBytes = Math.max(minBufferBytes * 2, config.getFrameSamples() * 8);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    config.getSampleRate(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                throw new IOException("AudioRecord initialization failed");
            }

            processor.reset();
            workerFailure = null;
            result = null;
            running = true;
            try {
                audioRecord.startRecording();
            } catch (RuntimeException failure) {
                audioRecord.release();
                audioRecord = null;
                throw new IOException("AudioRecord.startRecording failed", failure);
            }
            worker = new Thread(this::recordLoop, "vad-audio-record");
            worker.start();
        }
    }

    public LocalVadRecording stop() throws IOException {
        Thread thread;
        synchronized (stateLock) {
            if (worker == null) {
                throw new IllegalStateException("recorder is not running");
            }
            running = false;
            thread = worker;
            if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        }

        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while stopping recorder", interrupted);
        } finally {
            synchronized (stateLock) {
                if (audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                worker = null;
            }
        }

        if (workerFailure != null) {
            throw new IOException("recording failed", workerFailure);
        }
        result = new LocalVadRecording(
                pcmOutputFile,
                config.getSampleRate(),
                processor.finish());
        return result;
    }

    @Override
    public void close() throws IOException {
        synchronized (stateLock) {
            if (worker == null) {
                return;
            }
        }
        stop();
    }

    private void recordLoop() {
        int minBufferBytes = AudioRecord.getMinBufferSize(
                config.getSampleRate(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int samplesPerRead = Math.max(config.getFrameSamples() * 4, Math.max(1, minBufferBytes / 2));
        short[] pcm = new short[samplesPerRead];
        byte[] littleEndian = new byte[samplesPerRead * 2];

        try (FileOutputStream output = new FileOutputStream(pcmOutputFile, false)) {
            while (running) {
                int count = audioRecord.read(pcm, 0, pcm.length);
                if (count < 0) {
                    throw new IOException("AudioRecord.read failed: " + count);
                }
                if (count <= 0) {
                    continue;
                }
                processor.accept(pcm, 0, count);
                writeLittleEndian(pcm, count, littleEndian);
                output.write(littleEndian, 0, count * 2);
            }
        } catch (Throwable failure) {
            workerFailure = failure;
        }
    }

    private static void writeLittleEndian(short[] pcm, int count, byte[] output) {
        for (int i = 0; i < count; i++) {
            int value = pcm[i];
            output[i * 2] = (byte) (value & 0xff);
            output[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
    }
}
