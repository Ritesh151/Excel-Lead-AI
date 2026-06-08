package com.optimatrix.gsmcall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SuccessGreen = Color(0xFF27AE60)
private val ErrorRed = Color(0xFFC0392B)
private val WarningAmber = Color(0xFFD4A017)
private val NeutralGray = Color(0xFF95A5A6)
private val DarkNavy = Color(0xFF2C3E50)
private val LogBg = Color(0xFFF8F6F3)
private val OffWhiteDivider = Color(0xFFE0D6C8)

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
            .padding(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            StatusCard(state)
            ConnectionDashboardCard(state)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "GSM Call AI Dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DarkNavy
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusChip(label = "Permissions", ok = state.permissionsGranted)
                StatusChip(label = "Service", ok = state.serviceStatus != "idle")
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Backend: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    state.backendStatus,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = when (state.backendStatus) {
                        "online" -> SuccessGreen
                        "offline" -> ErrorRed
                        else -> WarningAmber
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("WS: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (state.websocketConnected) "connected" else "disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (state.websocketConnected) SuccessGreen else NeutralGray
                )
            }
            Text("Call: ${state.callState}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            if (state.callTimerSeconds > 0) {
                Text("Timer: ${state.callTimerSeconds}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (state.lastError.isNotBlank()) {
                Text(
                    state.lastError.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (ok) SuccessGreen else NeutralGray, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            "$label: ${if (ok) "OK" else "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) SuccessGreen else NeutralGray
        )
    }
}

@Composable
private fun ConnectionDashboardCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = DarkNavy)
            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Backend: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                val backendColor = when (state.backendStatus) {
                    "online" -> SuccessGreen
                    "offline" -> ErrorRed
                    "connecting", "reconnecting" -> WarningAmber
                    else -> NeutralGray
                }
                Text(state.backendStatus, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = backendColor)
                if (state.reconnectAttempt > 0) {
                    Text(" (${state.reconnectAttempt})", style = MaterialTheme.typography.bodySmall, color = NeutralGray)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WebSocket: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (state.websocketConnected) "CONNECTED" else "DISCONNECTED",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (state.websocketConnected) SuccessGreen else ErrorRed
                )
            }

            if (state.wifiConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WiFi: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("CONNECTED", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = SuccessGreen)
                    Text(" (${state.networkType})", style = MaterialTheme.typography.bodySmall, color = NeutralGray)
                }
                Text("Host: ${state.backendHost}:${state.backendPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TCP: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (state.tcpReachable) "REACHABLE" else "NOT REACHABLE",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.tcpReachable) SuccessGreen else ErrorRed
                    )
                    if (state.tcpLatencyMs >= 0) {
                        Text(" (${state.tcpLatencyMs}ms)", style = MaterialTheme.typography.bodySmall, color = NeutralGray)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Health: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (state.healthReachable) "OK" else "FAILED",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.healthReachable) SuccessGreen else ErrorRed
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WiFi: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("DISCONNECTED", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = ErrorRed)
                }
            }

            if (state.diagnosticsReport.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Diagnostics:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = DarkNavy)
                val lines = state.diagnosticsReport.lines().take(6)
                lines.forEach { line ->
                    Text(line.take(80), style = MaterialTheme.typography.bodySmall, color = NeutralGray)
                }
            }
        }
    }
}

@Composable
private fun CallDetailCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Active Call", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = DarkNavy)
            Spacer(modifier = Modifier.height(2.dp))
            Text("Phone: ${state.activeCallPhone.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Routing: ${state.audioRoutingState}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Playback: ${state.playbackState}${state.playbackStrategy.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Recording: ${state.recordingState}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            if (state.transcriptionText.isNotBlank()) {
                Text("Transcription: ${state.transcriptionText.take(200)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (state.detectedIntent.isNotBlank()) {
                Text(
                    "Intent: ${state.detectedIntent}${if (state.intentConfidence > 0f) " (${(state.intentConfidence * 100).toInt()}%)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.detectedIntent) {
                        "YES" -> SuccessGreen
                        "NO" -> ErrorRed
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun CampaignCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Campaign", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = DarkNavy)

            when {
                state.campaignStarting -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = DarkNavy
                        )
                        Text("Starting campaign…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                state.campaignStartError.isNotBlank() -> {
                    Text(
                        state.campaignStartError,
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.campaignRunning || state.campaignTotalLeads > 0 -> {
                    Text("ID: ${state.campaignId.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Progress: ${state.campaignProcessed}/${state.campaignTotalLeads}  YES=${state.campaignYesCount}  NO=${state.campaignNoCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LinearProgressIndicator(
                        progress = { (state.campaignProgress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = DarkNavy,
                        trackColor = OffWhiteDivider,
                    )
                }
                else -> {
                    Text(
                        "No active campaign — press \"Start automation\" below",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralGray
                    )
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
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) SuccessGreen else DarkNavy,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(text = if (granted) "Permissions ready" else "Request permissions")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartService,
                enabled = !campaignStarting && !campaignRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkNavy,
                    contentColor = Color.White,
                    disabledContainerColor = OffWhiteDivider,
                    disabledContentColor = NeutralGray
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = ErrorRed,
                    disabledContainerColor = OffWhiteDivider,
                    disabledContentColor = NeutralGray
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Stop automation")
            }
        }
        Button(
            onClick = onExportLogs,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Debug Console", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = DarkNavy)
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(logs.take(80)) { entry ->
                    Text(
                        entry,
                        color = DarkNavy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LogBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Tap to clear logs",
                modifier = Modifier
                    .clickable { onClearLogs() }
                    .align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelSmall,
                color = NeutralGray,
            )
        }
    }
}
