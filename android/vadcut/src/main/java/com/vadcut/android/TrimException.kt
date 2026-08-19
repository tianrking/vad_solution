package com.vadcut.android

class TrimException(
    val code: TrimErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
