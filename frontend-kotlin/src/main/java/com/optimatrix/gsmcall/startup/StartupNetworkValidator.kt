package com.optimatrix.gsmcall.startup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.optimatrix.gsmcall.network.NetworkDiagnosticsManager
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * StartupNetworkValidator — Runs before app automation can start
 *
 * VALIDATION GATE:
 *   Before automation starts, MUST pass all diagnostics
 *   User sees clear status: ✓ Connected or ✗ Failed with reason
 *
 * Used by:
 *   - MainActivity startup (on app launch)
 *   - CallAutomationService (before campaign starts)
 *   - User-triggered "Run Diagnostics" button
 */
class StartupNetworkValidator(private val context: Context) {

    companion object {
        private const val TAG = "StartupValidator"
    }

    sealed class ValidationState {
        object Idle : ValidationState()
        object InProgress : ValidationState()
        data class Success(
            val wifiConnected: Boolean,
            val tcpReachable: Boolean,
            val httpHealthOk: Boolean,
            val websocketReachable: Boolean,
            val latencyMs: Long,
        ) : ValidationState()
        data class Failure(
            val issues: List<String>,
            val recommendations: List<String>,
            val fullReport: String,
        ) : ValidationState()
        data class Error(val exception: Exception) : ValidationState()
    }

    private val _state = MutableStateFlow<ValidationState>(ValidationState.Idle)
    val state: StateFlow<ValidationState> = _state

    private val diagnostics = NetworkDiagnosticsManager(context)

    /**
     * MAIN ENTRY POINT
     * Run this at app startup or before campaign starts.
     * Validates on background thread, updates state flow.
     */
    fun validateAsync(onComplete: ((Boolean) -> Unit)? = null) {
        LogStore.log(TAG, "Starting async validation...")
        _state.value = ValidationState.InProgress

        // Run on background thread — don't block UI
        val thread = Thread {
            try {
                val result = diagnostics.validateFullStack()

                _state.value = if (result.success) {
                    LogStore.log(TAG, "✓ Validation PASSED")
                    ValidationState.Success(
                        wifiConnected = result.wifiConnected,
                        tcpReachable = result.backendTcpReachable,
                        httpHealthOk = result.httpHealthReachable,
                        websocketReachable = result.websocketReachable,
                        latencyMs = result.latencyMs,
                    )
                } else {
                    LogStore.log(TAG, "✗ Validation FAILED")
                    ValidationState.Failure(
                        issues = result.issues,
                        recommendations = result.recommendations,
                        fullReport = result.fullReport,
                    )
                }

                onComplete?.invoke(result.success)
            } catch (ex: Exception) {
                LogStore.log(TAG, "Validation exception: ${ex.javaClass.simpleName}: ${ex.message}")
                _state.value = ValidationState.Error(ex)
                onComplete?.invoke(false)
            }
        }
        thread.name = "NetworkValidation"
        thread.isDaemon = false
        thread.start()
    }

    /**
     * QUICK CHECK (non-blocking)
     * Run periodically during automation to ensure backend still reachable.
     */
    fun quickCheck(): Boolean {
        return diagnostics.quickHealthCheck()
    }

    /**
     * Check if currently validated and connected
     */
    fun isValidated(): Boolean {
        return _state.value is ValidationState.Success
    }

    /**
     * Get current diagnostic report (for UI display)
     */
    fun getReport(): String? {
        return when (val state = _state.value) {
            is ValidationState.Success -> {
                "✓ BACKEND CONNECTED\n" +
                        "  WiFi: ✓\n" +
                        "  TCP: ✓ ${System.currentTimeMillis()}\n" +
                        "  HTTP: ✓\n" +
                        "  WebSocket: ✓\n" +
                        "  Latency: ${state.latencyMs}ms"
            }
            is ValidationState.Failure -> {
                "✗ BACKEND NOT REACHABLE\n" +
                        "\nISSUES:\n" +
                        state.issues.mapIndexed { i, issue -> "${i+1}. $issue" }.joinToString("\n") +
                        "\n\nRECOMMENDATIONS:\n" +
                        state.recommendations.mapIndexed { i, rec -> "${i+1}. $rec" }.joinToString("\n") +
                        "\n\nFull Report:\n" +
                        state.fullReport
            }
            else -> null
        }
    }

    /**
     * Get status string for UI button/indicator
     */
    fun getStatusString(): String {
        return when (_state.value) {
            ValidationState.Idle -> "Not checked"
            ValidationState.InProgress -> "Validating..."
            is ValidationState.Success -> "✓ Connected"
            is ValidationState.Failure -> "✗ Not connected"
            is ValidationState.Error -> "✗ Error"
        }
    }
}

/**
 * ViewModel wrapper for use in UI (e.g., MainActivity)
 */
class NetworkValidationViewModel(private val context: Context) : ViewModel() {
    private val validator = StartupNetworkValidator(context)
    val state: StateFlow<StartupNetworkValidator.ValidationState> = validator.state

    fun validate() {
        validator.validateAsync()
    }

    fun getReport(): String? = validator.getReport()
    fun getStatus(): String = validator.getStatusString()
    fun isValidated(): Boolean = validator.isValidated()
    fun quickCheck(): Boolean = validator.quickCheck()
}
