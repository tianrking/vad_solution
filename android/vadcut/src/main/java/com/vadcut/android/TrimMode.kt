package com.vadcut.android

enum class TrimMode {
    /** Keep human speech using the bundled Silero neural VAD model. */
    SPEECH,

    /** Keep any sufficiently loud audio using an energy detector. */
    NON_SILENCE,
}
