package com.optimatrix.gsmcall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            StatusCard(state)
            ActionRow(
                state.permissionsGranted,
                onRequestPermissions,
                onStartService,
                onStopService,
                onExportLogs,
            )
            LogPanel(state.logs, onClearLogs)
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions granted: ${state.permissionsGranted}")
            Text("Service status: ${state.serviceStatus}")
            Text("Call state: ${state.callState}")
            Text("Audio routing: ${state.audioRoutingState}")
            Text("Backend: ${state.backendStatus}")
            if (state.exportedLogPath.isNotBlank()) {
                Text("Logs exported: ${state.exportedLogPath}")
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onRequestPermissions) {
            Text(text = if (granted) "Permissions ready" else "Request permissions")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStartService, modifier = Modifier.weight(1f)) {
                Text("Start automation")
            }
            Button(onClick = onStopService, modifier = Modifier.weight(1f)) {
                Text("Stop automation")
            }
        }
        Button(onClick = onExportLogs) {
            Text("Export logs")
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, onClearLogs: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Debug console", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.padding(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { entry ->
                    Text(entry, color = Color.White, modifier = Modifier.background(Color.DarkGray).padding(8.dp))
                }
            }
            Spacer(modifier = Modifier.padding(8.dp))
            Text("Tap logs to clear", modifier = Modifier.clickable { onClearLogs() })
        }
    }
}
