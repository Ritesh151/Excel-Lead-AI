package com.optimatrix.gsmcall.networking

import android.content.Context
import android.content.SharedPreferences
import com.optimatrix.gsmcall.BuildConfig
import com.optimatrix.gsmcall.utils.LogStore

/**
 * NetworkConfigManager — Dynamic backend configuration
 *
 * Reads from multiple sources (priority order):
 * 1. SharedPreferences (user-configured via settings)
 * 2. BuildConfig (set at build time via local.properties)
 * 3. Hardcoded defaults
 *
 * Usage:
 *   val config = NetworkConfigManager(context)
 *   val backendUrl = config.getBackendHttpUrl()      // http://10.216.39.119:3000
 *   val wsUrl = config.getBackendWebSocketUrl()     // ws://10.216.39.119:3000/
 *   val host = config.getBackendHost()              // 10.216.39.119
 *   val port = config.getBackendPort()              // 3000
 */
class NetworkConfigManager(context: Context) {
    
    companion object {
        private const val TAG = "NetworkConfigManager"
        private const val PREFS_NAME = "network_config"
        private const val KEY_BACKEND_HOST = "backend_host"
        private const val KEY_BACKEND_PORT = "backend_port"
        
        // Defaults from BuildConfig (injected at build time)
        private const val DEFAULT_BACKEND_HOST = BuildConfig.BACKEND_HOST
        private const val DEFAULT_BACKEND_PORT = BuildConfig.BACKEND_PORT
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get backend HTTP base URL
     * Example: "http://10.216.39.119:3000"
     */
    fun getBackendHttpUrl(): String {
        val host = getBackendHost()
        val port = getBackendPort()
        return "http://$host:$port"
    }
    
    /**
     * Get backend WebSocket URL
     * Example: "ws://10.216.39.119:3000/"
     */
    fun getBackendWebSocketUrl(): String {
        val host = getBackendHost()
        val port = getBackendPort()
        return "ws://$host:$port/"
    }
    
    /**
     * Get backend hostname/IP
     */
    fun getBackendHost(): String {
        val saved = prefs.getString(KEY_BACKEND_HOST, null)
        if (saved != null && saved.isNotBlank()) {
            LogStore.log(TAG, "Using saved backend host: $saved")
            return saved
        }
        
        val fromBuildConfig = DEFAULT_BACKEND_HOST
        if (fromBuildConfig.isNotBlank() && fromBuildConfig != "192.168.1.100") {
            LogStore.log(TAG, "Using BuildConfig backend host: $fromBuildConfig")
            return fromBuildConfig
        }
        
        LogStore.log(TAG, "Using hardcoded backend host: localhost")
        return "localhost"
    }
    
    /**
     * Get backend port
     */
    fun getBackendPort(): Int {
        val saved = prefs.getInt(KEY_BACKEND_PORT, -1)
        if (saved > 0) {
            LogStore.log(TAG, "Using saved backend port: $saved")
            return saved
        }
        
        val fromBuildConfig = DEFAULT_BACKEND_PORT
        if (fromBuildConfig > 0 && fromBuildConfig != 3000) {
            LogStore.log(TAG, "Using BuildConfig backend port: $fromBuildConfig")
            return fromBuildConfig
        }
        
        LogStore.log(TAG, "Using hardcoded backend port: 3000")
        return 3000
    }
    
    /**
     * Save custom backend configuration
     * Called when user changes settings
     */
    fun setBackendConfig(host: String, port: Int) {
        if (host.isBlank() || port <= 0) {
            LogStore.log(TAG, "Invalid config: host=$host port=$port")
            return
        }
        
        prefs.edit().apply {
            putString(KEY_BACKEND_HOST, host)
            putInt(KEY_BACKEND_PORT, port)
            apply()
        }
        
        LogStore.log(TAG, "✓ Backend config saved: $host:$port")
    }
    
    /**
     * Reset to BuildConfig defaults
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        LogStore.log(TAG, "✓ Config reset to defaults")
    }
    
    /**
     * Get full configuration summary
     */
    fun getConfigSummary(): String {
        return buildString {
            appendLine("════════════════════════════════════════")
            appendLine("NETWORK CONFIGURATION")
            appendLine("════════════════════════════════════════")
            appendLine("Backend Host: ${getBackendHost()}")
            appendLine("Backend Port: ${getBackendPort()}")
            appendLine("HTTP URL: ${getBackendHttpUrl()}")
            appendLine("WebSocket URL: ${getBackendWebSocketUrl()}")
            appendLine("BuildConfig Host: $DEFAULT_BACKEND_HOST")
            appendLine("BuildConfig Port: $DEFAULT_BACKEND_PORT")
            appendLine("════════════════════════════════════════")
        }
    }
}
