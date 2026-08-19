package com.vadcut.android

enum class TrimWarning {
    /** No activity was detected, so the complete input was retained. */
    NO_ACTIVITY_DETECTED_KEPT_ORIGINAL,

    /** The container did not report a duration; progress was therefore indeterminate. */
    INPUT_DURATION_UNKNOWN,
}
