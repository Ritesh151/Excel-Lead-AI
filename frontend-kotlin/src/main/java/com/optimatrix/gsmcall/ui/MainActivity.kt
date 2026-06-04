package com.optimatrix.gsmcall.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
            viewModel.updatePermissionsStatus(granted)
            if (!granted) {
                LogStore.log("MainActivity", "Permissions incomplete: ${results.filter { !it.value }.keys}")
            }
        }

        registerReceiver(statusReceiver, IntentFilter(CallAutomationService.ACTION_STATUS_UPDATE))
        registerReceiver(statusReceiver, IntentFilter(CallAutomationService.ACTION_LOG_UPDATE))

        setContent {
            MainScreen(
                state = viewModel.uiState,
                onRequestPermissions = { requestPermissions() },
                onStartService = { CallAutomationService.startService(this) },
                onStopService = { CallAutomationService.stopService(this) },
                onExportLogs = { exportLogs() },
                onClearLogs = { viewModel.clearLogs() }
            )
        }

        viewModel.updatePermissionsStatus(PermissionManager.hasAllPermissions(this))
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CallAutomationService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(CallAutomationService.EXTRA_CURRENT_STATUS) ?: "unknown"
                    viewModel.updateServiceState(status)
                }
                CallAutomationService.ACTION_LOG_UPDATE -> {
                    val logMessage = intent.getStringExtra(CallAutomationService.EXTRA_LOG_MESSAGE) ?: return
                    viewModel.appendLog(logMessage)
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
