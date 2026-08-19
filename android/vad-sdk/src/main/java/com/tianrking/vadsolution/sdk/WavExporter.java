package com.tianrking.vadsolution.sdk;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/** Writes selected mono PCM16 ranges to a standard little-endian WAV file. */
public final class WavExporter {
    private static final long MAX_WAV_DATA_BYTES = 0xFFFFFFFFL - 36L;

    private WavExporter() {
    }

    public static void export(
            File pcmFile,
            List<SpeechSegment> segments,
            int sampleRate,
            File wavFile) throws IOException {
        if (pcmFile == null || segments == null || wavFile == null || sampleRate <= 0) {
            throw new IllegalArgumentException("pcmFile, segments, wavFile and sampleRate are required");
        }
        if (!pcmFile.isFile()) {
            throw new IOException("PCM input does not exist: " + pcmFile);
        }
        File parent = wavFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("cannot create WAV output directory: " + parent);
        }

        long sourceSamples = pcmFile.length() / 2L;
        long selectedBytes = 0L;
        for (SpeechSegment segment : segments) {
            if (segment.getEndSample() > sourceSamples) {
                throw new IOException("speech range exceeds PCM input: " + segment);
            }
            selectedBytes += segment.getLengthSamples() * 2L;
            if (selectedBytes > MAX_WAV_DATA_BYTES) {
                throw new IOException("WAV output exceeds the 32-bit RIFF limit");
            }
        }

        try (RandomAccessFile output = new RandomAccessFile(wavFile, "rw");
             RandomAccessFile input = new RandomAccessFile(pcmFile, "r")) {
            output.setLength(0);
            writeHeader(output, sampleRate, 0);
            byte[] buffer = new byte[16 * 1024];
            for (SpeechSegment segment : segments) {
                long remaining = segment.getLengthSamples() * 2L;
                input.seek(segment.getStartSample() * 2L);
                while (remaining > 0) {
                    int requested = (int) Math.min(buffer.length, remaining);
                    int read = input.read(buffer, 0, requested);
                    if (read < 0) {
                        throw new IOException("unexpected end of PCM input");
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            output.seek(4);
            writeIntLE(output, 36 + selectedBytes);
            output.seek(40);
            writeIntLE(output, selectedBytes);
        }
    }

    private static void writeHeader(RandomAccessFile output, int sampleRate, long dataBytes) throws IOException {
        output.writeBytes("RIFF");
        writeIntLE(output, 36 + dataBytes);
        output.writeBytes("WAVE");
        output.writeBytes("fmt ");
        writeIntLE(output, 16);
        writeShortLE(output, 1);
        writeShortLE(output, 1);
        writeIntLE(output, sampleRate);
        writeIntLE(output, sampleRate * 2L);
        writeShortLE(output, 2);
        writeShortLE(output, 16);
        output.writeBytes("data");
        writeIntLE(output, dataBytes);
    }

    private static void writeIntLE(RandomAccessFile output, long value) throws IOException {
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
    }

    private static void writeShortLE(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
