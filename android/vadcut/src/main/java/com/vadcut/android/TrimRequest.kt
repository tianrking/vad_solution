package com.vadcut.android

import android.net.Uri

class TrimRequest private constructor(builder: Builder) {
    val inputUri: Uri = builder.inputUri
    val outputUri: Uri = builder.outputUri
    val config: TrimConfig = builder.config
    val manualTrimPlan: ManualTrimPlan? = builder.manualTrimPlan

    class Builder(
        internal val inputUri: Uri,
        internal val outputUri: Uri,
    ) {
        internal var config: TrimConfig = TrimConfig.voiceMemo()
        internal var manualTrimPlan: ManualTrimPlan? = null

        fun setConfig(value: TrimConfig) = apply { config = value }

        /**
         * Uses a caller-supplied manual edit plan instead of automatic VAD/energy detection.
         * [TrimConfig.fadeDurationMs] still controls edit-boundary fades; detector parameters are
         * ignored while a manual plan is present.
         */
        fun setManualTrimPlan(value: ManualTrimPlan) = apply { manualTrimPlan = value }

        /** Convenience method equivalent to [ManualTrimPlan.removeRanges]. */
        fun setRemovedRanges(value: List<AudioRange>) = apply {
            manualTrimPlan = ManualTrimPlan.removeRanges(value)
        }

        /** Vararg convenience overload for Kotlin and Java. */
        fun setRemovedRanges(vararg value: AudioRange) = setRemovedRanges(value.asList())

        /** Convenience method equivalent to [ManualTrimPlan.keepRanges]. */
        fun setKeptRanges(value: List<AudioRange>) = apply {
            manualTrimPlan = ManualTrimPlan.keepRanges(value)
        }

        /** Vararg convenience overload for Kotlin and Java. */
        fun setKeptRanges(vararg value: AudioRange) = setKeptRanges(value.asList())

        /** Clears any manual plan and restores automatic detection. */
        fun useAutomaticDetection() = apply { manualTrimPlan = null }

        fun build(): TrimRequest = TrimRequest(this)
    }
}
