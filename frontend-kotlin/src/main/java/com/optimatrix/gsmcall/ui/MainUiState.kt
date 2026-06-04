package com.optimatrix.gsmcall.ui

/**
 * MainUiState — Complete observable state for the dashboard UI.
 *
 * Updated live by:
 *   - LocalBroadcast from CallAutomationService (call states, logs)
 *   - WebSocket events from backend-node (transcription, campaign progress)
 *   - ApiClient health checks (backend connectivity)
 */
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
    val playbackState: String = "idle",        // idle | playing | played | failed
    val playbackStrategy: String = "",

    // Recording
    val recordingState: String = "idle",       // idle | recording | saved | failed

    // Transcription / Intent
    val transcriptionText: String = "",
    val detectedIntent: String = "",           // YES | NO | UNKNOWN | ""
    val intentConfidence: Float = 0f,

    // Campaign progress
    val campaignRunning: Boolean = false,
    val campaignStarting: Boolean = false,    // true while POST /api/adb/start is in-flight
    val campaignStartError: String = "",      // non-empty when start failed
    val campaignId: String = "",
    val campaignTotalLeads: Int = 0,
    val campaignProcessed: Int = 0,
    val campaignYesCount: Int = 0,
    val campaignNoCount: Int = 0,
    val campaignProgress: Int = 0,            // 0–100 percent

    // Backend connectivity
    val backendStatus: String = "offline",    // offline | connecting | online
    val websocketConnected: Boolean = false,

    // Logs (newest first)
    val logs: List<String> = emptyList(),

    // Export
    val exportedLogPath: String = "",
)
