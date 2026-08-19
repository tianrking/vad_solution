package com.vadcut.android

interface TrimListener {
    fun onProgress(progress: TrimProgress)
    fun onSuccess(result: TrimResult)
    fun onError(error: TrimException)
    fun onCancelled()
}
