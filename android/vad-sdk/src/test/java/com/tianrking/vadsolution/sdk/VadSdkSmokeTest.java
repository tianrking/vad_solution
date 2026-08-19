package com.tianrking.vadsolution.sdk;

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
        System.out.println("VadSdkSmokeTest passed");
    }

    private static void fillTone(short[] pcm, int start, int end, int amplitude) {
        for (int i = start; i < end; i++) {
            pcm[i] = (short) (Math.sin(i * 0.15) * amplitude);
        }
    }
}
