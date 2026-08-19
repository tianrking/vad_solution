package com.vadcut.android

import kotlinx.coroutines.Job

class TrimTask internal constructor(private val job: Job) {
    val isActive: Boolean
        get() = job.isActive

    fun cancel() {
        job.cancel()
    }
}
