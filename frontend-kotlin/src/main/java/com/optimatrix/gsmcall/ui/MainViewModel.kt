package com.optimatrix.gsmcall.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimatrix.gsmcall.NetworkConfig
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.network.ConnectionStatusEngine
import com.optimatrix.gsmcall.network.NetworkConnectivityCallback
import com.optimatrix.gsmcall.network.ReconnectEngine
import com.optimatrix.gsmcall.network.TcpConnectionTester
import com.optimatrix.gsmcall.networking.LanConnectivityValidator
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val apiClient = ApiClient(application)
    private val networking = NetworkingInitializer(application)

    private val connectionStatusEngine = ConnectionStatusEngine(application)
    private val networkCallback = NetworkConnectivityCallback(application)
    private val reconnectEngine = ReconnectEngine()
    private val tcpTester = TcpConnectionTester()
    private var networkListener: NetworkConnectivityCallback.OnNetworkChangeListener? = null

    private var healthPollJob: Job? = null
    private var campaignPollJob: Job? = null
    private var callTimerJob: Job? = null
    private var diagnosticsJob: Job? = null

    init {
        initConnectionState()
        registerNetworkCallback()
        initializeNetworking()
        startHealthPolling()
        startCampaignPolling()
        runStartupDiagnostics()
    }

    private fun initConnectionState() {
        _uiState.update { it.copy(
            backendHost = NetworkConfig.host,
            backendPort = NetworkConfig.port,
            connectionStatusText = "Initializing..."
        ) }
        LogStore.log("MainViewModel", "Network config: ${NetworkConfig.httpBaseUrl}")
        LogStore.log("MainViewModel", "WebSocket URL: ${NetworkConfig.wsUrl}")
    }

    private fun registerNetworkCallback() {
        val listener = object : NetworkConnectivityCallback.OnNetworkChangeListener {
            override fun onWifiConnected() {
                LogStore.log("MainViewModel", "WiFi connected")
                appendLog("WiFi connected")
                _uiState.update { it.copy(wifiConnected = true, networkType = "WiFi") }
                runDiagnostics()
            }

            override fun onWifiDisconnected() {
                LogStore.log("MainViewModel", "WiFi disconnected")
                appendLog("WiFi disconnected")
                _uiState.update { it.copy(wifiConnected = false, backendStatus = "offline", websocketConnected = false) }
            }

            override fun onWifiChanged(newNetwork: String) {
                LogStore.log("MainViewModel", "WiFi network changed")
                appendLog("WiFi network changed")
                runDiagnostics()
            }

            override fun onCellularConnected() {
                LogStore.log("MainViewModel", "Cellular data connected")
                appendLog("Cellular data active - ensure WiFi is also connected")
                _uiState.update { it.copy(networkType = "Cellular") }
            }

            override fun onNetworkLost() {
                LogStore.log("MainViewModel", "Network lost")
                appendLog("Network connection lost")
                _uiState.update { it.copy(wifiConnected = false, backendStatus = "offline") }
            }

            override fun onNetworkAvailable() {
                LogStore.log("MainViewModel", "Network available")
                runDiagnostics()
            }

            override fun onNetworkUnavailable() {
                LogStore.log("MainViewModel", "Network unavailable")
                _uiState.update { it.copy(wifiConnected = false) }
            }

            override fun onCaptivePortal() {
                LogStore.log("MainViewModel", "Captive portal detected")
                appendLog("Captive portal - may need to sign in")
            }
        }
        networkListener = listener
        networkCallback.addListener(listener)
        networkCallback.register()
        _uiState.update { it.copy(
            wifiConnected = networkCallback.isWifiConnected(),
            networkType = networkCallback.getActiveNetworkType()
        ) }
    }

    private fun runStartupDiagnostics() {
        diagnosticsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1000)
            LogStore.log("MainViewModel", "Running startup diagnostics...")
            appendLog("Running network diagnostics...")
            val result = connectionStatusEngine.runFullDiagnostics()
            withContext(Dispatchers.Main) {
                updateStateFromDiagnostics(result)
            }
        }
    }

    private fun runDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = connectionStatusEngine.runFullDiagnostics()
            withContext(Dispatchers.Main) {
                updateStateFromDiagnostics(result)
            }
        }
    }

    private fun updateStateFromDiagnostics(state: ConnectionStatusEngine.ConnectionState) {
        val backendStatusStr = when (state.backendStatus) {
            ConnectionStatusEngine.BackendStatus.ONLINE -> "online"
            ConnectionStatusEngine.BackendStatus.OFFLINE -> "offline"
            ConnectionStatusEngine.BackendStatus.CONNECTING -> "connecting"
            ConnectionStatusEngine.BackendStatus.DEGRADED -> "degraded"
            ConnectionStatusEngine.BackendStatus.RECONNECTING -> "reconnecting"
        }
        _uiState.update { it.copy(
            backendStatus = backendStatusStr,
            tcpReachable = state.tcpReachable,
            healthReachable = state.healthResponse,
            tcpLatencyMs = state.latencyMs,
            lastError = state.lastError ?: "",
            wifiConnected = it.wifiConnected || state.networkStatus.name.startsWith("WIFI"),
            backendHost = state.backendHost,
            backendPort = state.backendPort
        ) }
        if (state.tcpReachable) {
            LogStore.log("MainViewModel", "TCP reachable (${state.latencyMs}ms)")
        } else {
            LogStore.log("MainViewModel", "TCP unreachable: ${state.lastError}")
            appendLog("TCP unreachable: ${state.lastError ?: "unknown"}")
        }
    }

    private fun initializeNetworking() {
        LogStore.log("MainViewModel", "Initializing networking stack...")

        networking.initializeWebSocket(
            onStateChange = { state ->
                handleWebSocketStateChange(state)
            },
            onMessage = { event, payload ->
                handleWebSocketEventMap(event, payload)
            },
            onError = { error ->
                handleWebSocketError(error)
            }
        )
    }

    private fun handleWebSocketStateChange(state: SocketManagerProduction.ConnectionState) {
        LogStore.log("MainViewModel", "WebSocket state: $state")

        when (state) {
            SocketManagerProduction.ConnectionState.CONNECTED -> {
                _uiState.update { it.copy(
                    websocketConnected = true,
                    backendStatus = "online",
                    reconnectAttempt = 0
                ) }
                connectionStatusEngine.updateWebSocketStatus(ConnectionStatusEngine.WebSocketStatus.CONNECTED)
                appendLog("Backend connected (WebSocket)")
            }
            SocketManagerProduction.ConnectionState.CONNECTING -> {
                _uiState.update { it.copy(backendStatus = "connecting") }
                connectionStatusEngine.updateWebSocketStatus(ConnectionStatusEngine.WebSocketStatus.CONNECTING)
                appendLog("Connecting to backend...")
            }
            SocketManagerProduction.ConnectionState.RECONNECTING -> {
                val attempt = _uiState.value.reconnectAttempt + 1
                _uiState.update { it.copy(
                    backendStatus = "reconnecting",
                    reconnectAttempt = attempt
                ) }
                connectionStatusEngine.updateWebSocketStatus(ConnectionStatusEngine.WebSocketStatus.RECONNECTING)
                connectionStatusEngine.recordReconnectAttempt(attempt)
                appendLog("Reconnecting to backend (attempt $attempt)...")
            }
            SocketManagerProduction.ConnectionState.DISCONNECTED -> {
                _uiState.update { it.copy(
                    websocketConnected = false,
                    backendStatus = "offline"
                ) }
                connectionStatusEngine.updateWebSocketStatus(ConnectionStatusEngine.WebSocketStatus.DISCONNECTED)
                appendLog("Backend disconnected")
            }
            SocketManagerProduction.ConnectionState.FAILED -> {
                _uiState.update { it.copy(
                    websocketConnected = false,
                    backendStatus = "offline"
                ) }
                connectionStatusEngine.updateWebSocketStatus(ConnectionStatusEngine.WebSocketStatus.FAILED)
                appendLog("Cannot reach backend")
            }
        }
    }

    private fun handleWebSocketEventMap(event: String, payload: Map<String, Any?>) {
        LogStore.log("MainViewModel", "WebSocket event: $event")
        val jsonPayload = JSONObject(payload)
        handleWebSocketEvent(jsonPayload.toString())
    }

    private fun handleWebSocketError(error: String) {
        LogStore.log("MainViewModel", "WebSocket error: $error")
        appendLog("WebSocket error: $error")
        _uiState.update { it.copy(
            websocketConnected = false,
            lastError = error
        ) }
    }

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

    fun appendLog(message: String) {
        _uiState.update { s ->
            val newLogs = (listOf(message) + s.logs).take(300)
            s.copy(logs = newLogs)
        }
    }

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
                appendLog("Call started -> ${obj.optString("phone")}")
            }

            "call_connected" -> {
                _uiState.update { it.copy(callState = "connected") }
                appendLog("Call connected")
                startCallTimer()
            }

            "greeting_playing" -> {
                _uiState.update { it.copy(
                    playbackState    = "playing",
                    playbackStrategy = obj.optString("strategy"),
                ) }
                appendLog("Playing greeting (${obj.optString("strategy")})")
            }

            "greeting_played" -> {
                val ok = obj.optBoolean("success", true)
                _uiState.update { it.copy(playbackState = if (ok) "played" else "failed") }
                appendLog(if (ok) "Greeting played" else "Greeting playback had issues")
            }

            "recording_started" -> {
                _uiState.update { it.copy(recordingState = "recording") }
                appendLog("Recording customer response...")
            }

            "recording_saved" -> {
                _uiState.update { it.copy(recordingState = "saved") }
                appendLog("Recording saved (${obj.optInt("duration")}s)")
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
                appendLog("Transcription: \"${text.take(100)}\"")
            }

            "intent_detected" -> {
                val intent     = obj.optString("intent")
                val confidence = obj.optDouble("confidence", 0.0).toFloat()
                _uiState.update { it.copy(detectedIntent = intent, intentConfidence = confidence) }
                appendLog("Intent: $intent (${(confidence * 100).toInt()}%)")
            }

            "call_result", "call_completed" -> {
                val intent    = obj.optString("intent")
                val text      = obj.optString("transcription")
                _uiState.update { it.copy(
                    detectedIntent    = intent,
                    transcriptionText = text,
                    callState         = "completed",
                ) }
                appendLog("Call complete - Intent: $intent")
                stopCallTimer()
            }

            "call_failed" -> {
                _uiState.update { it.copy(callState = "failed") }
                appendLog("Call failed: ${obj.optString("error")}")
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
                appendLog("Campaign: $processed/$total  YES=$yes  NO=$no")
            }

            "campaign_done" -> {
                _uiState.update { it.copy(campaignRunning = false, campaignProgress = 100) }
                appendLog("Campaign complete")
            }

            "device_status" -> {
                appendLog("Device: ${obj.optString("serial")} -> ${obj.optString("status")}")
            }

            "android_connected" -> {
                _uiState.update { it.copy(websocketConnected = true, backendStatus = "online") }
                appendLog("WebSocket connected to backend")
            }

            "log" -> {
                appendLog("[Backend] ${obj.optString("message")}")
            }

            "connected" -> {
                _uiState.update { it.copy(websocketConnected = true, backendStatus = "online") }
            }

            "ws_connected"    -> _uiState.update { it.copy(websocketConnected = true,  backendStatus = "online")  }
            "ws_disconnected" -> _uiState.update { it.copy(websocketConnected = false, backendStatus = "offline") }

            else -> { }
        }
    }

    fun startCampaign(campaignName: String = "Android Campaign") {
        if (_uiState.value.campaignStarting || _uiState.value.campaignRunning) {
            appendLog("Campaign already starting or running")
            return
        }

        appendLog("Starting automation: $campaignName")
        LogStore.log("MainViewModel", "startCampaign: $campaignName")
        _uiState.update { it.copy(campaignStarting = true, campaignStartError = "") }

        viewModelScope.launch {
            appendLog("")
            appendLog("Validating LAN connectivity...")

            val validator = LanConnectivityValidator(getApplication())
            val result = validator.validate()
            appendLog(result.diagnosticsText)

            if (!result.isHealthy) {
                LogStore.log("MainViewModel", "LAN validation failed")
                appendLog("")
                appendLog("CONNECTIVITY ISSUES DETECTED:")
                result.issues.forEach { issue -> appendLog("  - $issue") }
                appendLog("")
                appendLog("HOW TO FIX:")
                result.recommendations.forEach { rec -> appendLog("  -> $rec") }
                appendLog("")
                appendLog("After fixing, click 'Start Automation' again.")

                _uiState.update { it.copy(
                    campaignStarting = false,
                    campaignStartError = result.issues.joinToString(", ")
                ) }
                return@launch
            }

            runTcpDiagnostics()

            LogStore.log("MainViewModel", "LAN validation passed")
            appendLog("")
            appendLog("LAN: All connectivity checks passed")
            appendLog("Starting campaign on backend...")
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
                    appendLog("CAMPAIGN STARTED SUCCESSFULLY")
                    appendLog("Campaign ID: $campaignId")
                    appendLog("Total Leads: $totalLeads")
                    LogStore.log("MainViewModel", "Campaign started: $campaignId ($totalLeads leads)")
                },
                onError = { error ->
                    _uiState.update { it.copy(
                        campaignStarting = false,
                        campaignStartError = error
                    ) }
                    appendLog("")
                    appendLog("CAMPAIGN START FAILED")
                    appendLog("Error: $error")
                    appendLog("")
                    appendLog("LAN connectivity was OK, but backend rejected the campaign.")
                    LogStore.log("MainViewModel", "Campaign start failed: $error")
                }
            )
        }
    }

    private suspend fun runTcpDiagnostics() {
        appendLog("TCP diagnostics...")
        val tcpResult = withContext(Dispatchers.IO) {
            tcpTester.testConnection()
        }
        _uiState.update { it.copy(
            tcpReachable = tcpResult.success,
            tcpLatencyMs = tcpResult.latencyMs,
            lastError = if (!tcpResult.success) tcpResult.failureReason ?: "" else ""
        ) }
        if (tcpResult.success) {
            appendLog("TCP: ${tcpResult.host}:${tcpResult.port} reachable (${tcpResult.latencyMs}ms)")
        } else {
            appendLog("TCP: ${tcpResult.host}:${tcpResult.port} NOT reachable - ${tcpResult.failureReason}")
            appendLog("  Category: ${tcpResult.failureCategory}")
            when (tcpResult.failureCategory) {
                TcpConnectionTester.FailureCategory.TIMEOUT -> appendLog("  Fix: Check firewall or backend process")
                TcpConnectionTester.FailureCategory.CONNECTION_REFUSED -> appendLog("  Fix: Backend not listening on port ${tcpResult.port}")
                TcpConnectionTester.FailureCategory.DNS_FAILURE -> appendLog("  Fix: Wrong IP address")
                TcpConnectionTester.FailureCategory.NO_ROUTE -> appendLog("  Fix: Different subnet or AP isolation")
                else -> appendLog("  Fix: Check network configuration")
            }
        }
    }

    fun stopCampaign() {
        appendLog("Stopping campaign...")
        LogStore.log("ViewModel", "stopCampaign requested")
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { apiClient.stopCampaign() } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(campaignRunning = false, campaignStarting = false) }
                appendLog(if (ok) "Campaign stop requested" else "Stop request sent")
            }
        }
    }

    fun updatePermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun updateExportPath(path: String) {
        _uiState.update { it.copy(exportedLogPath = path) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
        LogStore.snapshot()
    }

    private fun startHealthPolling() {
        healthPollJob?.cancel()
        healthPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val health = try { apiClient.checkHealth() } catch (_: Exception) { ApiClient.HealthResult(false) }
                _uiState.update { it.copy(
                    backendStatus = if (health.online) "online" else if (it.backendStatus != "offline") "offline" else it.backendStatus,
                    healthReachable = health.online
                ) }
                delay(15_000)
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
        LogStore.log("MainViewModel", "Cleaning up...")
        healthPollJob?.cancel()
        campaignPollJob?.cancel()
        callTimerJob?.cancel()
        diagnosticsJob?.cancel()
        networkCallback.unregister()
        networkListener?.let { networkCallback.removeListener(it) }
        connectionStatusEngine.destroy()
        reconnectEngine.disconnect()
        networking.shutdown()
    }
}
