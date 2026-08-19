package com.tianrking.vadsolution.sdk;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** Small dependency-free smoke test useful before an Android toolchain is available. */
public final class VadSdkSmokeTest {
    private VadSdkSmokeTest() {
    }

    public static void main(String[] args) {
        short[] pcm = new short[16_000 * 2];
        fillTone(pcm, 3_200, 6_400, 12_000);
        fillTone(pcm, 12_800, 16_000, 12_000);

        VadConfig config = VadConfig.builder()
                .setMinSpeechMs(80)
                .setMinSilenceMs(300)
                .setKeepSilenceMs(100)
                .build();
        PcmVadProcessor processor = VadSdk.createDefault(config);
        processor.accept(pcm, 0, 7_123);
        processor.accept(pcm, 7_123, pcm.length - 7_123);

        java.util.List<SpeechSegment> ranges = processor.finish();
        if (ranges.size() != 2) {
            throw new AssertionError("expected two speech ranges: " + ranges);
        }

        processor.reset();
        processor.accept(new short[321]);
        if (!processor.finish().isEmpty()) {
            throw new AssertionError("silence-only input should have no speech ranges");
        }
        testWavExporter();
        System.out.println("VadSdkSmokeTest passed");
    }

    private static void testWavExporter() {
        File pcm = null;
        File wav = null;
        try {
            pcm = File.createTempFile("vad-sdk-", ".pcm");
            wav = File.createTempFile("vad-sdk-", ".wav");
            try (RandomAccessFile output = new RandomAccessFile(pcm, "rw")) {
                for (int i = 0; i < 1_000; i++) {
                    output.write(i & 0xff);
                    output.write((i >>> 8) & 0xff);
                }
            }
            WavExporter.export(
                    pcm,
                    Arrays.asList(new SpeechSegment(100, 200), new SpeechSegment(400, 500)),
                    16_000,
                    wav);
            if (wav.length() != 44 + 400) {
                throw new AssertionError("unexpected WAV length: " + wav.length());
            }
            try (RandomAccessFile input = new RandomAccessFile(wav, "r")) {
                if (input.readUnsignedByte() != 'R'
                        || input.readUnsignedByte() != 'I'
                        || input.readUnsignedByte() != 'F'
                        || input.readUnsignedByte() != 'F') {
                    throw new AssertionError("WAV RIFF header is invalid");
                }
                input.seek(40);
                if (readIntLE(input) != 400) {
                    throw new AssertionError("WAV data length is invalid");
                }
            }
        } catch (Exception failure) {
            throw new AssertionError("WAV exporter test failed", failure);
        } finally {
            if (pcm != null) {
                pcm.delete();
            }
            if (wav != null) {
                wav.delete();
            }
        }
    }

    private static int readIntLE(RandomAccessFile input) throws java.io.IOException {
        return input.readUnsignedByte()
                | (input.readUnsignedByte() << 8)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 24);
    }

    private static void fillTone(short[] pcm, int start, int end, int amplitude) {
        for (int i = start; i < end; i++) {
            pcm[i] = (short) (Math.sin(i * 0.15) * amplitude);
        }
    }
}
