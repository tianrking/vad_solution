package com.vadcut.android

/** Defines how the ranges in a [ManualTrimPlan] are interpreted on the original input timeline. */
enum class ManualTrimMode {
    /** Delete the supplied ranges and keep their complement. */
    REMOVE_RANGES,

    /** Keep only the supplied ranges and delete their complement. */
    KEEP_RANGES,
}
