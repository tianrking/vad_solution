package com.vadcut.android.internal

internal interface ActivityDetector : AutoCloseable {
    fun score(samples: FloatArray, validSampleCount: Int): Float
    override fun close() = Unit
}
