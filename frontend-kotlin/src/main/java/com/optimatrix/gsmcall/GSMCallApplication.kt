package com.optimatrix.gsmcall

import android.app.Application
import com.optimatrix.gsmcall.crash.GlobalCrashHandler
import com.optimatrix.gsmcall.utils.LogStore

/**
 * GSMCallApplication — Custom Application class for global initialization
 *
 * Responsibilities:
 *   1. Initialize global crash handler
 *   2. Initialize logging system
 *   3. Set up coroutine exception handlers
 *   4. Initialize app-wide resources
 */
class GSMCallApplication : Application() {

    companion object {
        private const val TAG = "GSMCallApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // CRITICAL: Initialize crash handler FIRST (before anything else)
        try {
            GlobalCrashHandler.initialize(this)
            LogStore.log(TAG, "Global crash handler initialized")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize logging system
        try {
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
            LogStore.log(TAG, "GSM Call Application Started")
            LogStore.log(TAG, "Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            LogStore.log(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            LogStore.log(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            LogStore.log(TAG, "════════════════════════════════════════════════════════")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
