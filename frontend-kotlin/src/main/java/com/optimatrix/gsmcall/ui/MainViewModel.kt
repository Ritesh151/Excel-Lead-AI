package com.optimatrix.gsmcall.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.networking.NetworkingInitializer
import com.optimatrix.gsmcall.networking.SocketManagerProduction
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MainViewModel — Jetpack Compose state holder.
 *
 * Exposes [uiState] as a StateFlow so the Composable re-renders on any change.
 *
 * State is updated from three sources:
 *   1. WebSocket events from backend-node (NEW via NetworkingInitializer)
 *   2. LocalBroadcast from CallAutomationService → updateFromServiceStatus()
 *   3. Periodic backend health poll             → startHealthPolling()
 *
 * Networking Stack Integration:
 *   • NetworkingInitializer: Single integration point for all networking
 *   • SocketManagerProduction: WebSocket with auto-reconnect
 *   • NetworkDiagnosticsValidator: Pre-campaign network checks
 *   • ApiClientProduction: HTTP client with retry logic
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Legacy API client for backward compatibility
    private val apiClient = ApiClient(application)
    
    // New production networking stack
    private val networking = NetworkingInitializer(application)

    private var healthPollJob: Job? = null
    private var campaignPollJob: Job? = null
    private var callTimerJob: Job? = null

    init {
        initializeNetworking()
        startHealthPolling()
        startCampaignPolling()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Networking Initialization (NEW)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialize WebSocket connection with handlers
     */
    private fun initializeNetworking() {
        LogStore.log("MainViewModel", "📡 Initializing networking stack...")

        networking.initializeWebSocket(
            // Handle connection state changes
            onStateChange = { state ->
                handleWebSocketStateChange(state)
            },
            // Handle incoming events
            onMessage = { event, payload ->
                handleWebSocketEventMap(event, payload)
            },
            // Handle errors
            onError = { error ->
                handleWebSocketError(error)
            }
        )
    }

    /**
     * Map WebSocket state changes to UI state
     */
    private fun handleWebSocketStateChange(state: SocketManagerProduction.ConnectionState) {
        LogStore.log("MainViewModel", "📊 WebSocket state: $state")

        when (state) {
            SocketManagerProduction.ConnectionState.CONNECTED -> {
                _uiState.update { it.copy(
                    websocketConnected = true,
                    backendStatus = "online"
                ) }
                appendLog("✓ Backend connected (WebSocket)")
            }
            SocketManagerProduction.ConnectionState.CONNECTING -> {
                _uiState.update { it.copy(backendStatus = "connecting") }
                appendLog("📡 Connecting to backend…")
            }
            SocketManagerProduction.ConnectionState.RECONNECTING -> {
                _uiState.update { it.copy(backendStatus = "reconnecting") }
                appendLog("🔄 Reconnecting to backend…")
            }
            SocketManagerProduction.ConnectionState.DISCONNECTED -> {
                _uiState.update { it.copy(
                    websocketConnected = false,
                    backendStatus = "offline"
                ) }
                appendLog("⚠ Backend disconnected")
            }
            SocketManagerProduction.ConnectionState.FAILED -> {
                _uiState.update { it.copy(
                    websocketConnected = false,
                    backendStatus = "offline"
                ) }
                appendLog("❌ Cannot reach backend")
            }
        }
    }

    /**
     * Handle incoming WebSocket events from backend (Map version for networking stack)
     */
    private fun handleWebSocketEventMap(event: String, payload: Map<String, Any?>) {
        LogStore.log("MainViewModel", "← WebSocket: $event")

        // Convert Map to JSONObject for compatibility with existing handler
        val jsonPayload = JSONObject(payload)
        val jsonString = jsonPayload.toString()

        handleWebSocketEvent(jsonString)
    }

    /**
     * Handle WebSocket errors
     */
    private fun handleWebSocketError(error: String) {
        LogStore.log("MainViewModel", "❌ WebSocket error: $error")
        appendLog("❌ WebSocket error: $error")
        _uiState.update { it.copy(websocketConnected = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service broadcast handlers (called by MainActivity's BroadcastReceiver)
    // ─────────────────────────────────────────────────────────────────────────

    /** Handles ACTION_STATUS_UPDATE from CallAutomationService */
    fun updateFromServiceStatus(status: String) {
        _uiState.update { s ->
            s.copy(
                serviceStatus      = status,
                callState          = deriveCallState(status),
                audioRoutingState  = deriveRoutingState(status, s.audioRoutingState),
                playbackState      = derivePlaybackState(status, s.playbackState),
                recordingState     = deriveRecordingState(status, s.recordingState),
            )
        }

        when (status) {
            "call_connected"  -> startCallTimer()
            "call_ended", "completed", "error" -> stopCallTimer()
            "transcribed_YES" -> _uiState.update { it.copy(detectedIntent = "YES") }
            "transcribed_NO"  -> _uiState.update { it.copy(detectedIntent = "NO") }
            "transcribed_UNKNOWN" -> _uiState.update { it.copy(detectedIntent = "UNKNOWN") }
        }
    }

    /** Handles ACTION_LOG_UPDATE from CallAutomationService */
    fun appendLog(message: String) {
        _uiState.update { s ->
            val newLogs = (listOf(message) + s.logs).take(300)
            s.copy(logs = newLogs)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket JSON event handler (called by SocketManager callback in MainActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a raw WebSocket JSON string from backend-node and update state.
     * All backend WebSocket events pass through here.
     */
    fun handleWebSocketEvent(json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val event = obj.optString("event", "")

        when (event) {
            "call_started" -> {
                _uiState.update { it.copy(
                    activeCallPhone  = obj.optString("phone"),
                    callState        = "dialing",
                    transcriptionText = "",
                    detectedIntent   = "",
                    intentConfidence  = 0f,
                ) }
                appendLog("📞 Call started → ${obj.optString("phone")}")
            }

            "call_connected" -> {
                _uiState.update { it.copy(callState = "connected") }
                appendLog("✅ Call connected")
                startCallTimer()
            }

            "greeting_playing" -> {
                _uiState.update { it.copy(
                    playbackState    = "playing",
                    playbackStrategy = obj.optString("strategy"),
                ) }
                appendLog("🔊 Playing greeting (${obj.optString("strategy")})")
            }

            "greeting_played" -> {
                val ok = obj.optBoolean("success", true)
                _uiState.update { it.copy(playbackState = if (ok) "played" else "failed") }
                appendLog(if (ok) "✅ Greeting played" else "⚠ Greeting playback had issues")
            }

            "recording_started" -> {
                _uiState.update { it.copy(recordingState = "recording") }
                appendLog("🎤 Recording customer response…")
            }

            "recording_saved" -> {
                _uiState.update { it.copy(recordingState = "saved") }
                appendLog("💾 Recording saved (${obj.optInt("duration")}s)")
            }

            "transcription_done" -> {
                val text       = obj.optString("transcription")
                val intent     = obj.optString("intent")
                val confidence = obj.optDouble("confidence", 0.0).toFloat()
                _uiState.update { it.copy(
                    transcriptionText = text,
                    detectedIntent    = intent,
                    intentConfidence  = confidence,
                ) }
                appendLog("📝 Transcription: \"${text.take(100)}\"")
            }

            "intent_detected" -> {
                val intent     = obj.optString("intent")
                val confidence = obj.optDouble("confidence", 0.0).toFloat()
                _uiState.update { it.copy(detectedIntent = intent, intentConfidence = confidence) }
                appendLog("🎯 Intent: $intent (${(confidence * 100).toInt()}%)")
            }

            "call_result", "call_completed" -> {
                val intent    = obj.optString("intent")
                val text      = obj.optString("transcription")
                _uiState.update { it.copy(
                    detectedIntent    = intent,
                    transcriptionText = text,
                    callState         = "completed",
                ) }
                appendLog("✅ Call complete — Intent: $intent")
                stopCallTimer()
            }

            "call_failed" -> {
                _uiState.update { it.copy(callState = "failed") }
                appendLog("❌ Call failed: ${obj.optString("error")}")
                stopCallTimer()
            }

            "campaign_progress" -> {
                val processed = obj.optInt("processed")
                val total     = obj.optInt("total")
                val yes       = obj.optInt("yesCount")
                val no        = obj.optInt("noCount")
                val pct       = if (total > 0) (processed * 100 / total) else 0
                _uiState.update { it.copy(
                    campaignRunning   = true,
                    campaignId        = obj.optString("campaignId"),
                    campaignProcessed = processed,
                    campaignTotalLeads = total,
                    campaignYesCount  = yes,
                    campaignNoCount   = no,
                    campaignProgress  = pct,
                ) }
                appendLog("📊 Campaign: $processed/$total  YES=$yes  NO=$no")
            }

            "campaign_done" -> {
                _uiState.update { it.copy(campaignRunning = false, campaignProgress = 100) }
                appendLog("🏁 Campaign complete")
            }

            "device_status" -> {
                appendLog("📱 Device: ${obj.optString("serial")} → ${obj.optString("status")}")
            }

            "android_connected" -> {
                _uiState.update { it.copy(websocketConnected = true, backendStatus = "online") }
                appendLog("🔗 WebSocket connected to backend")
            }

            "log" -> {
                appendLog("[Backend] ${obj.optString("message")}")
            }

            "connected" -> {
                _uiState.update { it.copy(websocketConnected = true, backendStatus = "online") }
            }

            "ws_connected"    -> _uiState.update { it.copy(websocketConnected = true,  backendStatus = "online")  }
            "ws_disconnected" -> _uiState.update { it.copy(websocketConnected = false, backendStatus = "offline") }

            else -> { /* ignore unknown events */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Campaign control — called by MainActivity "Start/Stop automation" buttons
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start campaign — COMPLETE FLOW with LAN connectivity validation
     *
     * Sequence:
     *   1. Validate LAN connectivity (4-point check):
     *      • WiFi connected
     *      • TCP socket to backend
     *      • HTTP /health endpoint
     *      • WebSocket TCP reachable
     *   2. If healthy, start campaign via NetworkingInitializer
     *   3. Update UI with results or detailed error diagnostics
     */
    fun startCampaign(campaignName: String = "Android Campaign") {
        if (_uiState.value.campaignStarting || _uiState.value.campaignRunning) {
            appendLog("⚠ Campaign already starting or running — ignoring duplicate request")
            return
        }

        appendLog("🚀 Starting automation: $campaignName")
        LogStore.log("MainViewModel", "startCampaign: $campaignName")

        _uiState.update { it.copy(campaignStarting = true, campaignStartError = "") }

        viewModelScope.launch {
            // ─ STEP 1: Validate LAN connectivity before campaign start
            appendLog("")
            appendLog("🔍 Validating LAN connectivity…")
            
            val validator = com.optimatrix.gsmcall.networking.LanConnectivityValidator(getApplication())
            val result = validator.validate()
            
            // Show diagnostics regardless of outcome
            appendLog(result.diagnosticsText)
            
            viewModelScope.launch(Dispatchers.Main) {
                if (!result.isHealthy) {
                    // Connectivity issues detected
                    LogStore.log("MainViewModel", "❌ LAN validation failed")
                    appendLog("")
                    appendLog("❌ CONNECTIVITY ISSUES DETECTED:")
                    result.issues.forEach { issue ->
                        appendLog("  ❌ $issue")
                    }
                    appendLog("")
                    appendLog("💡 HOW TO FIX:")
                    result.recommendations.forEach { rec ->
                        appendLog("  → $rec")
                    }
                    appendLog("")
                    appendLog("🔧 After fixing, click 'Start Automation' again.")

                    _uiState.update { it.copy(
                        campaignStarting = false,
                        campaignStartError = result.issues.joinToString(", ")
                    ) }
                    return@launch
                }

                // ─ STEP 2: LAN connectivity passed, start campaign
                LogStore.log("MainViewModel", "✓ LAN validation passed")
                appendLog("")
                appendLog("✓ LAN: All connectivity checks passed")
                appendLog("✓ Starting campaign on backend…")
                appendLog("")

                networking.startCampaign(
                    name = campaignName,
                    onSuccess = { campaignId, totalLeads ->
                        _uiState.update { it.copy(
                            campaignStarting = false,
                            campaignStartError = "",
                            campaignRunning = true,
                            campaignId = campaignId,
                            campaignTotalLeads = totalLeads,
                            campaignProgress = 0,
                        ) }
                        appendLog("════════════════════════════════════════")
                        appendLog("✅ CAMPAIGN STARTED SUCCESSFULLY")
                        appendLog("════════════════════════════════════════")
                        appendLog("Campaign ID: $campaignId")
                        appendLog("Total Leads: $totalLeads")
                        appendLog("Status: Running")
                        appendLog("════════════════════════════════════════")
                        LogStore.log("MainViewModel", "Campaign started: $campaignId ($totalLeads leads)")
                    },
                    onError = { error ->
                        _uiState.update { it.copy(
                            campaignStarting = false,
                            campaignStartError = error
                        ) }
                        appendLog("")
                        appendLog("❌ CAMPAIGN START FAILED (Backend)")
                        appendLog("Error: $error")
                        appendLog("")
                        appendLog("⚠️  LAN connectivity was OK, but backend rejected the campaign.")
                        appendLog("")
                        appendLog("Possible causes:")
                        appendLog("  • Backend crashed during validation")
                        appendLog("  • MongoDB connection lost")
                        appendLog("  • AI-Python service offline")
                        appendLog("  • Invalid campaign data")
                        appendLog("")
                        appendLog("Try:")
                        appendLog("  1. Restart backend: npm start")
                        appendLog("  2. Check backend logs for errors")
                        appendLog("  3. Click 'Start Automation' again")
                        LogStore.log("MainViewModel", "Campaign start failed: $error")
                    }
                )
            }
        }
    }

    /**
     * Stop the running campaign and the local Android service.
     */
    fun stopCampaign() {
        appendLog("🛑 Stopping campaign…")
        LogStore.log("ViewModel", "stopCampaign requested")
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { apiClient.stopCampaign() } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(campaignRunning = false, campaignStarting = false) }
                appendLog(if (ok) "✅ Campaign stop requested" else "⚠ Stop request sent (may not have reached backend)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions + service control
    // ─────────────────────────────────────────────────────────────────────────

    fun updatePermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun updateExportPath(path: String) {
        _uiState.update { it.copy(exportedLogPath = path) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
        LogStore.snapshot().let { /* LogStore itself keeps its buffer */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backend health polling (every 10 s)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startHealthPolling() {
        healthPollJob?.cancel()
        healthPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val health = try { apiClient.checkHealth() } catch (_: Exception) { ApiClient.HealthResult(false) }
                _uiState.update { it.copy(
                    backendStatus = if (health.online) "online" else "offline",
                ) }
                delay(10_000)
            }
        }
    }

    private fun startCampaignPolling() {
        campaignPollJob?.cancel()
        campaignPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val campaign = try { apiClient.fetchCampaignStatus() } catch (_: Exception) { null }
                if (campaign != null) {
                    _uiState.update { it.copy(
                        campaignRunning = campaign.isRunning,
                        campaignId = campaign.campaignId ?: "",
                        campaignTotalLeads = campaign.totalLeads,
                        campaignProcessed = campaign.processedLeads,
                        campaignYesCount = campaign.yesCount,
                        campaignNoCount = campaign.noCount,
                        campaignProgress = campaign.progressPercent,
                    ) }
                }
                delay(15_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Call timer
    // ─────────────────────────────────────────────────────────────────────────

    private fun startCallTimer() {
        callTimerJob?.cancel()
        _uiState.update { it.copy(callTimerSeconds = 0) }
        callTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _uiState.update { it.copy(callTimerSeconds = it.callTimerSeconds + 1) }
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State derivation helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun deriveCallState(status: String) = when {
        status.contains("connected")  -> "connected"
        status.contains("ringing")    -> "ringing"
        status.contains("dialing")    -> "dialing"
        status.contains("ended")      -> "ended"
        status.contains("completed")  -> "completed"
        status.contains("error")      -> "error"
        else -> status
    }

    private fun deriveRoutingState(status: String, current: String) = when (status) {
        "routing_audio"  -> "setting_up"
        "routing_ready"  -> "ready"
        "call_ended"     -> "restored"
        else             -> current
    }

    private fun derivePlaybackState(status: String, current: String) = when (status) {
        "playing_greeting"  -> "playing"
        "greeting_played"   -> "played"
        "playing_thank_you" -> "playing_thanks"
        "error_missing_audio" -> "file_missing"
        else -> current
    }

    private fun deriveRecordingState(status: String, current: String) = when (status) {
        "recording"       -> "recording"
        "uploading"       -> "uploading"
        "recording_failed" -> "failed"
        else -> current
    }

    override fun onCleared() {
        super.onCleared()
        LogStore.log("MainViewModel", "🛑 Cleaning up…")
        healthPollJob?.cancel()
        campaignPollJob?.cancel()
        callTimerJob?.cancel()
        networking.shutdown()  // NEW: Shutdown networking stack (WebSocket + cleanup)
    }
}
