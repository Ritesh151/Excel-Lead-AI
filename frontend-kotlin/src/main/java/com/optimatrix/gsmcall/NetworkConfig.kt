package com.optimatrix.gsmcall

import com.optimatrix.gsmcall.BuildConfig

/**
 * NetworkConfig — Central network configuration for backend communication.
 *
 * Override via local.properties:
 *   BACKEND_HOST=10.45.106.119
 *   BACKEND_PORT=3000
 *
 * Or via BuildConfig (Gradle):
 *   buildConfigField "String", "BACKEND_HOST", "\"10.45.106.119\""
 *   buildConfigField "int", "BACKEND_PORT", "3000"
 *
 * IMPORTANT FOR ANDROID:
 *   ✓ Use LAN IP address (e.g., 10.45.106.119), NOT localhost/127.0.0.1
 *   ✓ Use HTTP for development (configured in network_security_config.xml)
 *   ✓ Backend must be running on 0.0.0.0:3000
 *
 * Paths:
 *   HTTP:  http://10.45.106.119:3000/health
 *   WS:    ws://10.45.106.119:3000/socket.io/
 *   API:   http://10.45.106.119:3000/api/...
 */
object NetworkConfig {
    // Read from BuildConfig (set in build.gradle or local.properties)
    val host: String = BuildConfig.BACKEND_HOST.takeIf { it.isNotBlank() } ?: "10.45.106.119"
    val port: Int = BuildConfig.BACKEND_PORT.takeIf { it > 0 } ?: 3000

    // HTTP base URL for REST API
    val httpBaseUrl: String = "http://$host:$port"

    // WebSocket URL for backend WebSocket (ws:// protocol)
    val wsUrl: String = "ws://$host:$port/"

    // AI Python service
    val aiPythonUrl: String = "http://$host:8000"

    // Validation
    init {
        if (host.contains("localhost") || host.contains("127.0.0.1")) {
            android.util.Log.w(
                "NetworkConfig",
                "⚠️  WARNING: Using localhost address ($host). This will NOT work on Android devices. " +
                "Use LAN IP instead (e.g., 10.45.106.119)"
            )
        }
    }

    override fun toString(): String = """
        NetworkConfig:
          Host: $host
          Port: $port
          HTTP URL: $httpBaseUrl
          WS URL: $wsUrl
          AI Python: $aiPythonUrl
    """.trimIndent()
}

