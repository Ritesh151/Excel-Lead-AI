package com.optimatrix.gsmcall.ui

data class MainUiState(
    // Permissions
    val permissionsGranted: Boolean = false,

    // Service
    val serviceStatus: String = "idle",

    // Call
    val callState: String = "idle",
    val activeCallPhone: String = "",
    val callTimerSeconds: Int = 0,

    // Audio
    val audioRoutingState: String = "uninitialized",
    val playbackState: String = "idle",
    val playbackStrategy: String = "",

    // Recording
    val recordingState: String = "idle",

    // Transcription / Intent
    val transcriptionText: String = "",
    val detectedIntent: String = "",
    val intentConfidence: Float = 0f,

    // Campaign progress
    val campaignRunning: Boolean = false,
    val campaignStarting: Boolean = false,
    val campaignStartError: String = "",
    val campaignId: String = "",
    val campaignTotalLeads: Int = 0,
    val campaignProcessed: Int = 0,
    val campaignYesCount: Int = 0,
    val campaignNoCount: Int = 0,
    val campaignProgress: Int = 0,

    // Backend connectivity (legacy fields)
    val backendStatus: String = "offline",
    val websocketConnected: Boolean = false,

    // Enhanced connection status
    val connectionStatusText: String = "",
    val backendHost: String = "",
    val backendPort: Int = 0,
    val networkType: String = "",
    val tcpLatencyMs: Long = -1,
    val tcpReachable: Boolean = false,
    val healthReachable: Boolean = false,
    val reconnectAttempt: Int = 0,
    val lastError: String = "",
    val wifiConnected: Boolean = false,

    // Diagnostics report
    val diagnosticsReport: String = "",

    // Logs
    val logs: List<String> = emptyList(),

    // Export
    val exportedLogPath: String = "",
)
