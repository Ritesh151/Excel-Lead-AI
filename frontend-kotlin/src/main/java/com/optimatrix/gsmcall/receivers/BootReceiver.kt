package com.optimatrix.gsmcall.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.optimatrix.gsmcall.services.CallAutomationService
import com.optimatrix.gsmcall.utils.LogStore

/**
 * BootReceiver — Auto-start CallAutomationService on device boot.
 *
 * Triggers on:
 *   - BOOT_COMPLETED (device power on)
 *   - MY_PACKAGE_REPLACED (app update)
 *
 * Ensures the automation service is always running after reboot,
 * important for Samsung devices with aggressive app killer.
 */
    
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        LogStore.log("BootReceiver", "Received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LogStore.log("BootReceiver", "Starting CallAutomationService after boot/update")
            CallAutomationService.startService(context)
        }
    }
}

