package com.vadcut.android

enum class NoSpeechPolicy {
    /** Export the complete audio and include a warning in the result. */
    KEEP_ORIGINAL,

    /** Stop with [TrimErrorCode.NO_SPEECH_DETECTED]. */
    FAIL,
}
