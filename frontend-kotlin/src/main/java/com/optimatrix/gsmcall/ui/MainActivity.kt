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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val viewModel: MainViewModel by viewModels()
    private var isReceiverRegistered = AtomicBoolean(false)  // FIX 1.6: Track registration state

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
        
        // FIX 1.6: Track registration state with try-catch
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            isReceiverRegistered.set(true)
            LogStore.log("MainActivity", "statusReceiver registered")
        } catch (ex: Exception) {
            LogStore.log("MainActivity", "statusReceiver registration failed: ${ex.message}")
            isReceiverRegistered.set(false)
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            MainScreen(
                state = state,
                onRequestPermissions = { requestPermissions() },
                onStartService = {
                    // 1. Start the Android foreground service (handles in-call audio + recording)
                    CallAutomationService.startService(this@MainActivity)
                    // 2. Tell the backend to start the campaign (fetches Excel, queues ADB calls)
                    viewModel.startCampaign("Android Campaign")
                },
                onStopService = {
                    CallAutomationService.stopService(this@MainActivity)
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
        // FIX 1.6: Track unregistration state to prevent double-unregister
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                unregisterReceiver(statusReceiver)
                LogStore.log("MainActivity", "statusReceiver unregistered")
            } catch (ex: Exception) {
                LogStore.log("MainActivity", "statusReceiver unregister error: ${ex.message}")
            }
        }
        super.onDestroy()
    }
}
