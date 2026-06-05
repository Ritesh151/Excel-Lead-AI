package com.optimatrix.gsmcall.crash

import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineCrashHandler — Captures ALL coroutine exceptions
 *
 * Prevents coroutine crashes from crashing the entire app.
 * All coroutine exceptions are logged and handled gracefully.
 */
class CoroutineCrashHandler {

    companion object {
        private const val TAG = "CoroutineHandler"

        /**
         * Create a coroutine exception handler that logs crashes without crashing app
         * Usage: CoroutineScope(SupervisorJob() + CoroutineCrashHandler.createHandler())
         */
        fun createHandler(): CoroutineExceptionHandler {
            return CoroutineExceptionHandler { context, exception ->
                handleCoroutineException(context, exception)
            }
        }

        private fun handleCoroutineException(context: CoroutineContext, exception: Throwable) {
            LogStore.log(TAG, "Coroutine Exception: ${exception.javaClass.simpleName}")
            LogStore.log(TAG, "Message: ${exception.message}")
            LogStore.log(TAG, "Context: ${context[Job]}")
            LogStore.log(TAG, "Stack: ${exception.stackTraceToString().take(500)}")

            // Log full stack for debugging
            exception.printStackTrace()
        }
    }
}
