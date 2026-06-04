package com.optimatrix.gsmcall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    state: MainUiState,
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            StatusCard(state)
            CallDetailCard(state)
            CampaignCard(state)
            ActionRow(
                granted = state.permissionsGranted,
                onRequestPermissions = onRequestPermissions,
                onStartService = onStartService,
                onStopService = onStopService,
                onExportLogs = onExportLogs,
                campaignStarting = state.campaignStarting,
                campaignRunning  = state.campaignRunning,
            )
            LogPanel(state.logs, onClearLogs, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GSM Call AI Dashboard", style = MaterialTheme.typography.titleMedium)
            Text("Permissions: ${if (state.permissionsGranted) "OK" else "Required"}")
            Text("Service: ${state.serviceStatus}")
            Text("Backend: ${state.backendStatus}  |  WebSocket: ${if (state.websocketConnected) "connected" else "disconnected"}")
            Text("Call state: ${state.callState}")
            if (state.callTimerSeconds > 0) {
                Text("Call timer: ${state.callTimerSeconds}s")
            }
            if (state.exportedLogPath.isNotBlank()) {
                Text("Logs exported: ${state.exportedLogPath}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CallDetailCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active call", style = MaterialTheme.typography.titleSmall)
            Text("Phone: ${state.activeCallPhone.ifBlank { "—" }}")
            Text("Routing: ${state.audioRoutingState}")
            Text("Playback: ${state.playbackState} ${state.playbackStrategy.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}")
            Text("Recording: ${state.recordingState}")
            if (state.transcriptionText.isNotBlank()) {
                Text("Transcription: ${state.transcriptionText.take(200)}")
            }
            if (state.detectedIntent.isNotBlank()) {
                Text(
                    "Intent: ${state.detectedIntent}" +
                        if (state.intentConfidence > 0f) " (${(state.intentConfidence * 100).toInt()}%)" else ""
                )
            }
        }
    }
}

@Composable
private fun CampaignCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Campaign", style = MaterialTheme.typography.titleSmall)

            when {
                state.campaignStarting -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Starting campaign on backend…")
                    }
                }
                state.campaignStartError.isNotBlank() -> {
                    Text(
                        "❌ ${state.campaignStartError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.campaignRunning || state.campaignTotalLeads > 0 -> {
                    Text("ID: ${state.campaignId.ifBlank { "—" }}")
                    Text("Progress: ${state.campaignProcessed}/${state.campaignTotalLeads}  YES=${state.campaignYesCount}  NO=${state.campaignNoCount}")
                    LinearProgressIndicator(
                        progress = { (state.campaignProgress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    Text("No active campaign — press \"Start automation\" below")
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    granted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onExportLogs: () -> Unit,
    campaignStarting: Boolean = false,
    campaignRunning: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text(text = if (granted) "✅ Permissions ready" else "⚠ Request permissions")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartService,
                enabled = !campaignStarting && !campaignRunning,
                modifier = Modifier.weight(1f),
            ) {
                if (campaignStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(
                    when {
                        campaignStarting -> "Starting…"
                        campaignRunning  -> "Running…"
                        else             -> "Start automation"
                    }
                )
            }
            Button(
                onClick = onStopService,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stop automation")
            }
        }
        Button(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
            Text("Export logs")
        }
    }
}

@Composable
private fun LogPanel(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Debug console", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(logs.take(80)) { entry ->
                    Text(
                        entry,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.DarkGray)
                            .padding(6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Tap to clear logs",
                modifier = Modifier.clickable { onClearLogs() },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
