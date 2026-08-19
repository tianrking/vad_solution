package com.vadcut.android

/** Immutable detector and editing configuration. Use [Builder] from Java. */
class TrimConfig private constructor(builder: Builder) {
    val mode: TrimMode = builder.mode
    val speechStartThreshold: Float = builder.speechStartThreshold
    val speechEndThreshold: Float = builder.speechEndThreshold
    val minimumSpeechDurationMs: Long = builder.minimumSpeechDurationMs
    val minimumSilenceDurationMs: Long = builder.minimumSilenceDurationMs
    val paddingBeforeMs: Long = builder.paddingBeforeMs
    val paddingAfterMs: Long = builder.paddingAfterMs
    val fadeDurationMs: Long = builder.fadeDurationMs
    val energyThresholdDb: Float = builder.energyThresholdDb
    val noSpeechPolicy: NoSpeechPolicy = builder.noSpeechPolicy
    val verifyModelIntegrity: Boolean = builder.verifyModelIntegrity

    class Builder {
        internal var mode: TrimMode = TrimMode.SPEECH
        internal var speechStartThreshold: Float = 0.55f
        internal var speechEndThreshold: Float = 0.35f
        internal var minimumSpeechDurationMs: Long = 96
        internal var minimumSilenceDurationMs: Long = 700
        internal var paddingBeforeMs: Long = 180
        internal var paddingAfterMs: Long = 250
        internal var fadeDurationMs: Long = 8
        internal var energyThresholdDb: Float = -45f
        internal var noSpeechPolicy: NoSpeechPolicy = NoSpeechPolicy.KEEP_ORIGINAL
        internal var verifyModelIntegrity: Boolean = true

        constructor()

        constructor(config: TrimConfig) {
            mode = config.mode
            speechStartThreshold = config.speechStartThreshold
            speechEndThreshold = config.speechEndThreshold
            minimumSpeechDurationMs = config.minimumSpeechDurationMs
            minimumSilenceDurationMs = config.minimumSilenceDurationMs
            paddingBeforeMs = config.paddingBeforeMs
            paddingAfterMs = config.paddingAfterMs
            fadeDurationMs = config.fadeDurationMs
            energyThresholdDb = config.energyThresholdDb
            noSpeechPolicy = config.noSpeechPolicy
            verifyModelIntegrity = config.verifyModelIntegrity
        }

        fun setMode(value: TrimMode) = apply { mode = value }
        fun setSpeechStartThreshold(value: Float) = apply { speechStartThreshold = value }
        fun setSpeechEndThreshold(value: Float) = apply { speechEndThreshold = value }
        fun setMinimumSpeechDurationMs(value: Long) = apply { minimumSpeechDurationMs = value }
        fun setMinimumSilenceDurationMs(value: Long) = apply { minimumSilenceDurationMs = value }
        fun setPaddingBeforeMs(value: Long) = apply { paddingBeforeMs = value }
        fun setPaddingAfterMs(value: Long) = apply { paddingAfterMs = value }
        fun setFadeDurationMs(value: Long) = apply { fadeDurationMs = value }
        fun setEnergyThresholdDb(value: Float) = apply { energyThresholdDb = value }
        fun setNoSpeechPolicy(value: NoSpeechPolicy) = apply { noSpeechPolicy = value }
        fun setVerifyModelIntegrity(value: Boolean) = apply { verifyModelIntegrity = value }

        fun build(): TrimConfig {
            require(speechStartThreshold in 0f..1f) { "speechStartThreshold must be within [0, 1]" }
            require(speechEndThreshold in 0f..1f) { "speechEndThreshold must be within [0, 1]" }
            require(speechEndThreshold <= speechStartThreshold) {
                "speechEndThreshold must be <= speechStartThreshold"
            }
            require(minimumSpeechDurationMs >= 0) { "minimumSpeechDurationMs must be >= 0" }
            require(minimumSilenceDurationMs >= 0) { "minimumSilenceDurationMs must be >= 0" }
            require(paddingBeforeMs >= 0) { "paddingBeforeMs must be >= 0" }
            require(paddingAfterMs >= 0) { "paddingAfterMs must be >= 0" }
            require(fadeDurationMs >= 0) { "fadeDurationMs must be >= 0" }
            require(energyThresholdDb in -96f..0f) { "energyThresholdDb must be within [-96, 0]" }
            return TrimConfig(this)
        }
    }

    companion object {
        @JvmStatic
        fun fromPreset(preset: TrimPreset): TrimConfig = when (preset) {
            TrimPreset.CONSERVATIVE -> Builder()
                .setMinimumSilenceDurationMs(1_200)
                .setPaddingBeforeMs(250)
                .setPaddingAfterMs(350)
                .build()

            TrimPreset.VOICE_MEMO -> Builder().build()

            TrimPreset.AGGRESSIVE -> Builder()
                .setSpeechStartThreshold(0.60f)
                .setSpeechEndThreshold(0.40f)
                .setMinimumSilenceDurationMs(350)
                .setPaddingBeforeMs(100)
                .setPaddingAfterMs(140)
                .build()
        }

        @JvmStatic
        fun voiceMemo(): TrimConfig = fromPreset(TrimPreset.VOICE_MEMO)
    }
}
