package com.vadcut.android

enum class TrimPreset {
    /** Retains longer pauses and generous speech edges. */
    CONSERVATIVE,

    /** Recommended default for meetings, interviews and voice notes. */
    VOICE_MEMO,

    /** Removes shorter pauses and produces a tighter edit. */
    AGGRESSIVE,
}
