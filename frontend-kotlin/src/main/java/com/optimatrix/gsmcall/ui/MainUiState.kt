package com.optimatrix.gsmcall.ui

data class MainUiState(
    val permissionsGranted: Boolean,
    val serviceStatus: String,
    val callState: String,
    val audioRoutingState: String,
    val backendStatus: String,
    val logs: List<String>,
    val exportedLogPath: String,
)
