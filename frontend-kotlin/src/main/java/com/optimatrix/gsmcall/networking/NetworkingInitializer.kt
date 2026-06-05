package com.optimatrix.gsmcall.networking

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.launch

/**
 * NetworkingInitializer — Unified networking setup for ViewModel
 *
 * Provides single point of integration for all networking components:
 *   • NetworkConfigManager (configuration)
 *   • SocketManagerProduction (WebSocket)
 *   • ApiClientProduction (REST)
 *   • NetworkDiagnosticsValidator (validation)
 *
 * Usage in ViewModel:
 *   val networking = NetworkingInitializer(context)
 *   networking.initializeWebSocket(onStateChange = {... })
 *   networking.runDiagnostics { report -> ... }
 *   networking.startCampaign(name) { ... }
 */
class NetworkingInitializer(private val context: Context) {

    companion object {
        private const val TAG = "NetworkingInitializer"
    }

    val configManager = NetworkConfigManager(context)
    val socketManager = SocketManagerProduction(context)
    val apiClient = ApiClientProduction(context, configManager)
    val validator = NetworkDiagnosticsValidator(context, configManager)

    /**
     * Initialize WebSocket with listener
     */
    fun initializeWebSocket(
        onStateChange: (SocketManagerProduction.ConnectionState) -> Unit,
        onMessage: (event: String, payload: Map<String, Any?>) -> Unit,
        onError: (error: String) -> Unit
    ) {
        socketManager.setListener(object : SocketManagerProduction.SocketListener {
            override fun onStateChange(state: SocketManagerProduction.ConnectionState) {
                LogStore.log(TAG, "📊 WebSocket state: $state")
                onStateChange(state)
            }

            override fun onMessage(event: String, payload: org.json.JSONObject) {
                val map = payload.toMap()
                onMessage(event, map)
            }

            override fun onError(error: String) {
                LogStore.log(TAG, "❌ WebSocket error: $error")
                onError(error)
            }
        })

        socketManager.connect()
    }

    /**
     * Run network diagnostics before automation
     */
    fun runDiagnostics(
        viewModel: ViewModel,
        onResult: (report: NetworkDiagnosticsValidator.DiagnosticsReport) -> Unit
    ) {
        LogStore.log(TAG, "🔍 Running network diagnostics...")
        
        viewModel.viewModelScope.launch {
            try {
                val report = validator.validate()
                onResult(report)
            } catch (e: Exception) {
                LogStore.log(TAG, "❌ Diagnostics error: ${e.message}")
            }
        }
    }

    /**
     * Start campaign with proper error handling
     */
    fun startCampaign(
        name: String = "Android Campaign",
        onSuccess: (campaignId: String, leads: Int) -> Unit,
        onError: (error: String) -> Unit
    ) {
        LogStore.log(TAG, "🚀 Starting campaign: $name")
        
        apiClient.startCampaign(
            campaignName = name,
            onSuccess = { id, leads ->
                LogStore.log(TAG, "✓ Campaign started: $id ($leads leads)")
                onSuccess(id, leads)
            },
            onError = { error ->
                LogStore.log(TAG, "✗ Campaign start failed: $error")
                onError(error)
            }
        )
    }

    /**
     * Check backend health
     */
    fun checkHealth(onResult: (isHealthy: Boolean, details: String) -> Unit) {
        apiClient.checkHealth(onResult)
    }

    /**
     * Disconnect and cleanup
     */
    fun shutdown() {
        LogStore.log(TAG, "Shutting down networking...")
        socketManager.disconnect()
    }
}

// Extension to convert JSONObject to Map
private fun org.json.JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = get(key)
    }
    return map
}
