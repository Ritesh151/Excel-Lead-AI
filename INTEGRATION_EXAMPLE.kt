// ════════════════════════════════════════════════════════════════════════════════
// INTEGRATION EXAMPLE — How to use the new networking stack in your ViewModel
// ════════════════════════════════════════════════════════════════════════════════

package com.optimatrix.gsmcall.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimatrix.gsmcall.networking.NetworkingInitializer
import com.optimatrix.gsmcall.networking.SocketManagerProduction
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MainViewModelNew — Example integration with new networking stack
 *
 * This shows how to integrate:
 *   • NetworkingInitializer (single integration point)
 *   • SocketManagerProduction (WebSocket)
 *   • NetworkDiagnosticsValidator (pre-campaign checks)
 *   • ApiClientProduction (REST)
 */
class MainViewModelNew(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // Networking stack
    private val networking = NetworkingInitializer(application)

    // UI State
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────

    init {
        // Initialize WebSocket immediately when ViewModel created
        initializeWebSocket()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket Initialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializeWebSocket() {
        LogStore.log(TAG, "📡 Initializing WebSocket...")

        networking.initializeWebSocket(
            // Handle connection state changes
            onStateChange = { state ->
                handleWebSocketStateChange(state)
            },
            // Handle incoming events
            onMessage = { event, payload ->
                handleWebSocketEvent(event, payload)
            },
            // Handle errors
            onError = { error ->
                handleWebSocketError(error)
            }
        )
    }

    private fun handleWebSocketStateChange(state: SocketManagerProduction.ConnectionState) {
        LogStore.log(TAG, "📊 WebSocket state changed: $state")

        _uiState.update { it.copy(
            webSocketState = state.toString(),
            backendConnected = state == SocketManagerProduction.ConnectionState.CONNECTED
        ) }

        when (state) {
            SocketManagerProduction.ConnectionState.CONNECTED -> {
                LogStore.log(TAG, "✓ WebSocket connected")
                appendLog("✓ Backend connected")
            }
            SocketManagerProduction.ConnectionState.DISCONNECTED -> {
                LogStore.log(TAG, "✗ WebSocket disconnected")
                appendLog("✗ Backend disconnected")
            }
            SocketManagerProduction.ConnectionState.RECONNECTING -> {
                LogStore.log(TAG, "🔄 WebSocket reconnecting...")
                appendLog("🔄 Attempting to reconnect...")
            }
            SocketManagerProduction.ConnectionState.FAILED -> {
                LogStore.log(TAG, "❌ WebSocket connection failed")
                appendLog("❌ Cannot reach backend")
            }
            SocketManagerProduction.ConnectionState.CONNECTING -> {
                LogStore.log(TAG, "📡 WebSocket connecting...")
                appendLog("📡 Connecting to backend...")
            }
        }
    }

    private fun handleWebSocketEvent(event: String, payload: Map<String, Any?>) {
        LogStore.log(TAG, "← Received WebSocket event: $event")

        when (event) {
            "call_started" -> {
                val phone = payload["phone"] as? String ?: "unknown"
                appendLog("📞 Call started → $phone")
            }
            "call_connected" -> {
                appendLog("✅ Call connected")
            }
            "transcription_done" -> {
                val text = payload["transcription"] as? String ?: ""
                val intent = payload["intent"] as? String ?: "UNKNOWN"
                appendLog("📝 Transcription: $text")
                appendLog("🎯 Intent: $intent")
            }
            "campaign_progress" -> {
                val processed = payload["processed"] as? Int ?: 0
                val total = payload["total"] as? Int ?: 0
                appendLog("📊 Campaign: $processed/$total")
            }
            "call_completed" -> {
                appendLog("✅ Call complete")
            }
            // ... handle other events
        }
    }

    private fun handleWebSocketError(error: String) {
        LogStore.log(TAG, "❌ WebSocket error: $error")
        appendLog("❌ Error: $error")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Campaign Automation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start automation — called when user clicks "Start Automation" button
     *
     * IMPORTANT: Runs network diagnostics first!
     */
    fun startAutomation() {
        if (_uiState.value.campaignRunning) {
            LogStore.log(TAG, "Campaign already running")
            return
        }

        LogStore.log(TAG, "🚀 Starting automation...")
        _uiState.update { it.copy(campaignStarting = true) }
        appendLog("🚀 Starting campaign...")

        // Run network diagnostics in background
        viewModelScope.launch {
            networking.runDiagnostics(this@MainViewModelNew) { report ->
                if (!report.isHealthy) {
                    // Issues detected
                    LogStore.log(TAG, "❌ Network diagnostics failed")
                    
                    appendLog("")
                    appendLog("❌ NETWORK ISSUES DETECTED:")
                    report.issues.forEach { appendLog("  • $it") }
                    appendLog("")
                    appendLog("💡 HOW TO FIX:")
                    report.recommendations.forEach { appendLog("  • $it") }
                    appendLog("")
                    
                    _uiState.update { it.copy(
                        campaignStarting = false,
                        campaignError = report.issues.joinToString(", ")
                    ) }
                    return@runDiagnostics
                }

                // All diagnostics passed
                LogStore.log(TAG, "✓ Network diagnostics passed")
                appendLog("✓ Network: OK")
                appendLog("✓ Starting campaign on backend...")

                // Start campaign
                networking.startCampaign(
                    name = "Android Campaign",
                    onSuccess = { campaignId, totalLeads ->
                        handleCampaignStarted(campaignId, totalLeads)
                    },
                    onError = { error ->
                        handleCampaignStartFailed(error)
                    }
                )
            }
        }
    }

    private fun handleCampaignStarted(campaignId: String, totalLeads: Int) {
        LogStore.log(TAG, "✓ Campaign started: $campaignId ($totalLeads leads)")
        
        _uiState.update { it.copy(
            campaignRunning = true,
            campaignStarting = false,
            campaignId = campaignId,
            campaignTotalLeads = totalLeads,
            campaignError = ""
        ) }

        appendLog("")
        appendLog("════════════════════════════════════════")
        appendLog("✓ CAMPAIGN STARTED")
        appendLog("════════════════════════════════════════")
        appendLog("ID: $campaignId")
        appendLog("Leads: $totalLeads")
        appendLog("Status: Running")
        appendLog("════════════════════════════════════════")
    }

    private fun handleCampaignStartFailed(error: String) {
        LogStore.log(TAG, "✗ Campaign start failed: $error")
        
        _uiState.update { it.copy(
            campaignRunning = false,
            campaignStarting = false,
            campaignError = error
        ) }

        appendLog("")
        appendLog("❌ CAMPAIGN START FAILED")
        appendLog("Error: $error")
        appendLog("")
        appendLog("Check:")
        appendLog("  • Backend is running: npm start")
        appendLog("  • Firewall allows port 3000")
        appendLog("  • Backend IP is correct in settings")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop Automation
    // ─────────────────────────────────────────────────────────────────────────

    fun stopAutomation() {
        LogStore.log(TAG, "Stopping campaign...")
        
        _uiState.update { it.copy(
            campaignRunning = false
        ) }

        appendLog("🛑 Campaign stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health Check
    // ─────────────────────────────────────────────────────────────────────────

    fun checkBackendHealth() {
        LogStore.log(TAG, "Checking backend health...")

        networking.checkHealth { isHealthy, details ->
            _uiState.update { it.copy(
                backendHealthy = isHealthy,
                backendStatus = details
            ) }

            appendLog("🏥 Backend: $details")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        _uiState.update { state ->
            val newLogs = (listOf(message) + state.logs).take(200)
            state.copy(logs = newLogs)
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        LogStore.log(TAG, "ViewModel cleared, shutting down networking...")
        networking.shutdown()
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// UI State Data Class
// ════════════════════════════════════════════════════════════════════════════════

data class MainUiState(
    // Backend connection state
    val webSocketState: String = "DISCONNECTED",
    val backendConnected: Boolean = false,
    val backendHealthy: Boolean = false,
    val backendStatus: String = "checking...",

    // Campaign state
    val campaignRunning: Boolean = false,
    val campaignStarting: Boolean = false,
    val campaignId: String = "",
    val campaignTotalLeads: Int = 0,
    val campaignProcessed: Int = 0,
    val campaignError: String = "",

    // UI
    val logs: List<String> = emptyList(),
)

// ════════════════════════════════════════════════════════════════════════════════
// Usage in Composable
// ════════════════════════════════════════════════════════════════════════════════

/*
@Composable
fun MainScreen(viewModel: MainViewModelNew) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Status indicators
        Row {
            StatusIndicator(
                label = "Backend",
                connected = uiState.backendConnected,
                status = uiState.backendStatus
            )
            Spacer(modifier = Modifier.width(16.dp))
            StatusIndicator(
                label = "WebSocket",
                connected = uiState.webSocketState == "CONNECTED"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Campaign controls
        if (!uiState.campaignRunning) {
            Button(
                onClick = { viewModel.startAutomation() },
                enabled = !uiState.campaignStarting && uiState.backendConnected
            ) {
                Text(if (uiState.campaignStarting) "Starting..." else "Start Automation")
            }
        } else {
            Button(onClick = { viewModel.stopAutomation() }) {
                Text("Stop Campaign")
            }
            Text("Campaign: ${uiState.campaignProcessed}/${uiState.campaignTotalLeads}")
        }

        // Logs
        Spacer(modifier = Modifier.height(16.dp))
        Text("Logs:")
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(uiState.logs) { log ->
                Text(log, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
*/

// ════════════════════════════════════════════════════════════════════════════════
