package com.optimatrix.gsmcall.networking

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.*

/**
 * ConnectivityStartupValidator — Validates backend connectivity at app startup.
 *
 * Runs non-blocking checks:
 *   ✓ TCP connectivity
 *   ✓ HTTP /health endpoint
 *   ✓ WebSocket initialization
 *   ✓ Network diagnostics
 *
 * Updates UI state:
 *   "Checking backend..."
 *   "Backend: connected"
 *   "Backend: disconnected" (with retry button)
 *
 * Usage in MainActivity.onCreate():
 *   ConnectivityStartupValidator(context).validateAndConnect { connected ->
 *       if (connected) {
 *           updateUI("Backend connected ✓")
 *       } else {
 *           updateUI("Backend unreachable — retrying...")
 *       }
 *   }
 */
class ConnectivityStartupValidator(private val context: Context) {

    data class ValidationResult(
        val backendReachable: Boolean = false,
        val tcpConnected: Boolean = false,
        val httpHealthOk: Boolean = false,
        val websocketReady: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val isHealthy: Boolean = backendReachable && httpHealthOk

        override fun toString(): String = """
            Startup Validation Result:
              Backend Reachable: $backendReachable
              TCP Connected: $tcpConnected
              HTTP /health: $httpHealthOk
              WebSocket Ready: $websocketReady
              Error: ${errorMessage ?: "None"}
        """.trimIndent()
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun validateAndConnect(
        onResult: (ValidationResult) -> Unit,
        autoRetry: Boolean = true
    ) {
        LogStore.log("ConnectivityValidator", "Starting startup validation (autoRetry=$autoRetry)")
        
        scope.launch {
            try {
                val result = _performValidation()
                
                // Update UI on main thread
                mainHandler.post {
                    LogStore.log("ConnectivityValidator", "Validation complete: $result")
                    onResult(result)
                    
                    // If validation passed, initialize Socket.IO
                    if (result.isHealthy) {
                        LogStore.log("ConnectivityValidator", "Backend healthy — initializing WebSocket")
                        SocketManager.initialize()
                        SocketManager.connect()
                    } else if (autoRetry) {
                        LogStore.log("ConnectivityValidator", "Backend unhealthy — scheduling retry in 3s")
                        mainHandler.postDelayed({
                            validateAndConnect(onResult, autoRetry = true)
                        }, 3000)
                    }
                }
            } catch (e: Exception) {
                LogStore.log("ConnectivityValidator", "Validation exception: ${e.message}")
                mainHandler.post {
                    onResult(ValidationResult(
                        errorMessage = e.message ?: "Unknown error"
                    ))
                }
            }
        }
    }

    private suspend fun _performValidation(): ValidationResult {
        return withContext(Dispatchers.Default) {
            val result = ValidationResult()

            // 1. Run diagnostics
            LogStore.log("ConnectivityValidator", "Running network diagnostics...")
            val diagnostics = NetworkDiagnosticsManager(context)
            val diagResult = diagnostics.runFullDiagnostics()

            // 2. TCP check
            val tcpOk = diagResult.tcpReachable
            LogStore.log("ConnectivityValidator", "TCP check: ${if (tcpOk) "OK ✓" else "FAILED ✗"}")

            // 3. HTTP health check
            val httpOk = diagResult.httpHealthOk
            LogStore.log("ConnectivityValidator", "HTTP /health: ${if (httpOk) "OK ✓" else "FAILED ✗"}")

            // 4. Overall backend reachability
            val backendOk = tcpOk || httpOk
            LogStore.log("ConnectivityValidator", "Backend reachable: ${if (backendOk) "YES ✓" else "NO ✗"}")

            result.copy(
                backendReachable = backendOk,
                tcpConnected = tcpOk,
                httpHealthOk = httpOk,
                websocketReady = diagResult.websocketConnectable,
                errorMessage = diagResult.errorLog.firstOrNull()
            )
        }
    }

    fun cancel() {
        scope.cancel()
        LogStore.log("ConnectivityValidator", "Validator cancelled")
    }
}
