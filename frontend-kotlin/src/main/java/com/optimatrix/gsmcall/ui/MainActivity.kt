package com.optimatrix.gsmcall.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.optimatrix.gsmcall.permissions.PermissionManager
import com.optimatrix.gsmcall.services.CallAutomationService
import com.optimatrix.gsmcall.utils.LogStore

class MainActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val granted = results.entries.all { it.value }
            viewModel.updatePermissionsGranted(granted)
            if (!granted) {
                LogStore.log("MainActivity", "Permissions incomplete: ${results.filter { !it.value }.keys}")
            }
        }

        val filter = IntentFilter().apply {
            addAction(CallAutomationService.ACTION_STATUS_UPDATE)
            addAction(CallAutomationService.ACTION_LOG_UPDATE)
            addAction(CallAutomationService.ACTION_WS_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            MainScreen(
                state = state,
                onRequestPermissions = { requestPermissions() },
                onStartService = {
                    // 1. Start the Android foreground service (handles in-call audio + recording)
                    CallAutomationService.startService(this)
                    // 2. Tell the backend to start the campaign (fetches Excel, queues ADB calls)
                    viewModel.startCampaign("Android Campaign")
                },
                onStopService = {
                    CallAutomationService.stopService(this)
                    viewModel.stopCampaign()
                },
                onExportLogs = { exportLogs() },
                onClearLogs = { viewModel.clearLogs() },
            )
        }

        viewModel.updatePermissionsGranted(PermissionManager.hasAllPermissions(this))
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CallAutomationService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(CallAutomationService.EXTRA_CURRENT_STATUS) ?: "unknown"
                    viewModel.updateFromServiceStatus(status)
                }
                CallAutomationService.ACTION_LOG_UPDATE -> {
                    val logMessage = intent.getStringExtra(CallAutomationService.EXTRA_LOG_MESSAGE) ?: return
                    viewModel.appendLog(logMessage)
                }
                CallAutomationService.ACTION_WS_UPDATE -> {
                    val json = intent.getStringExtra(CallAutomationService.EXTRA_WS_JSON) ?: return
                    viewModel.handleWebSocketEvent(json)
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = PermissionManager.requiredPermissions(this)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun exportLogs() {
        val file = LogStore.exportLogs(applicationContext)
        viewModel.updateExportPath(file?.absolutePath ?: "")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
