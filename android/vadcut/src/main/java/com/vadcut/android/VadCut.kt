package com.vadcut.android

import android.content.Context
import com.vadcut.android.internal.TrimCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VadCut private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val coordinator = TrimCoordinator(appContext)

    /** Kotlin coroutine API. Progress is delivered on the main thread. */
    @JvmSynthetic
    suspend fun trim(
        request: TrimRequest,
        onProgress: (TrimProgress) -> Unit = {},
    ): TrimResult = coordinator.trim(request, onProgress)

    /** Java-friendly asynchronous API. Every listener method runs on the main thread. */
    fun trimAsync(request: TrimRequest, listener: TrimListener): TrimTask {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            try {
                val result = coordinator.trim(request, listener::onProgress)
                withContext(Dispatchers.Main.immediate) { listener.onSuccess(result) }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { listener.onCancelled() }
            } catch (error: TrimException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { listener.onError(error) }
            } catch (error: Throwable) {
                val wrapped = TrimException(TrimErrorCode.UNKNOWN, "Unexpected VadCut failure", error)
                withContext(NonCancellable + Dispatchers.Main.immediate) { listener.onError(wrapped) }
            }
        }
        return TrimTask(job)
    }

    companion object {
        @JvmStatic
        fun with(context: Context): VadCut = VadCut(context)

        const val VERSION: String = "0.1.0"
    }
}
