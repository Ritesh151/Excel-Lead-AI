package com.optimatrix.gsmcall.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    val permissionsGranted = mutableStateOf(false)
    val serviceStatus = mutableStateOf("idle")
    val callState = mutableStateOf("waiting")
    val audioRoutingState = mutableStateOf("uninitialized")
    val backendStatus = mutableStateOf("offline")
    val logs = mutableStateListOf<String>()
    val exportedLogPath = mutableStateOf("")

    val uiState get() = MainUiState(
        permissionsGranted = permissionsGranted.value,
        serviceStatus = serviceStatus.value,
        callState = callState.value,
        audioRoutingState = audioRoutingState.value,
        backendStatus = backendStatus.value,
        logs = logs.toList(),
        exportedLogPath = exportedLogPath.value,
    )

    fun updatePermissionsStatus(granted: Boolean) {
        permissionsGranted.value = granted
    }

    fun updateServiceState(status: String) {
        serviceStatus.value = status
    }

    fun updateCallState(state: String) {
        callState.value = state
    }

    fun appendLog(message: String) {
        logs.add(0, message)
        if (logs.size > 200) {
            logs.removeLast()
        }
    }

    fun updateExportPath(path: String) {
        exportedLogPath.value = path
    }

    fun clearLogs() {
        logs.clear()
    }
}
