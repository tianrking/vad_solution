package com.vadcut.sample.java;

import android.net.Uri;

import com.vadcut.android.AudioRange;
import com.vadcut.android.ManualTrimPlan;
import com.vadcut.android.TrimConfig;
import com.vadcut.android.TrimRequest;

import java.util.List;

/** Compiled examples for caller-supplied ranges on the original input timeline. */
public final class ManualTrimExamples {
    private ManualTrimExamples() { }

    public static TrimRequest removeRanges(Uri input, Uri output, List<AudioRange> rangesToDelete) {
        return new TrimRequest.Builder(input, output)
                .setManualTrimPlan(ManualTrimPlan.removeRanges(rangesToDelete))
                .setConfig(new TrimConfig.Builder().setFadeDurationMs(8L).build())
                .build();
    }

    public static TrimRequest keepRanges(Uri input, Uri output, List<AudioRange> rangesToKeep) {
        return new TrimRequest.Builder(input, output)
                .setKeptRanges(rangesToKeep)
                .setConfig(new TrimConfig.Builder().setFadeDurationMs(8L).build())
                .build();
    }
}
