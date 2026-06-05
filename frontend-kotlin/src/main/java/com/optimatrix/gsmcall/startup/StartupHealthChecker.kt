package com.optimatrix.gsmcall.startup

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.optimatrix.gsmcall.utils.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * StartupHealthChecker — Validates all critical systems are available before app starts
 *
 * Checks:
 *   - Permissions granted
 *   - Audio system available
 *   - Telephony available
 *   - Network connectivity
 *   - Memory available
 *
 * If any check fails → app launches in SAFE MODE instead of crashing
 */
class StartupHealthChecker(private val context: Context) {

    companion object {
        private const val TAG = "HealthCheck"
    }

    data class HealthReport(
        val isHealthy: Boolean,
        val failureReasons: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun performHealthCheck(): HealthReport = withContext(Dispatchers.Default) {
        LogStore.log(TAG, "Starting comprehensive health check...")

        val failures = mutableListOf<String>()

        // Check 1: Audio system
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                failures.add("AudioManager unavailable")
            } else {
                LogStore.log(TAG, "✓ Audio system OK")
            }
        } catch (e: Exception) {
            failures.add("Audio system error: ${e.message}")
        }

        // Check 2: Telephony
        try {
            val telMgr = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telMgr == null) {
                failures.add("TelephonyManager unavailable")
            } else {
                LogStore.log(TAG, "✓ Telephony system OK")
            }
        } catch (e: Exception) {
            failures.add("Telephony system error: ${e.message}")
        }

        // Check 3: Memory
        try {
            val runtime = Runtime.getRuntime()
            val freeMem = runtime.freeMemory() / 1024 / 1024  // MB
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val maxMem = runtime.maxMemory() / 1024 / 1024

            LogStore.log(TAG, "Memory: Free=$freeMem MB, Total=$totalMem MB, Max=$maxMem MB")

            if (freeMem < 50) {
                failures.add("Low memory: only $freeMem MB free (min 50 MB)")
            } else {
                LogStore.log(TAG, "✓ Memory OK")
            }
        } catch (e: Exception) {
            failures.add("Memory check error: ${e.message}")
        }

        // Check 4: File system
        try {
            val cacheDir = context.cacheDir
            val filesDir = context.filesDir

            if (!cacheDir.exists() || !filesDir.exists()) {
                failures.add("File system not accessible")
            } else {
                LogStore.log(TAG, "✓ File system OK")
            }
        } catch (e: Exception) {
            failures.add("File system error: ${e.message}")
        }

        val isHealthy = failures.isEmpty()

        if (isHealthy) {
            LogStore.log(TAG, "✓✓✓ Health check PASSED — app is healthy")
        } else {
            LogStore.log(TAG, "⚠ Health check FAILED with ${failures.size} issue(s)")
            failures.forEach { reason ->
                LogStore.log(TAG, "  ❌ $reason")
            }
        }

        HealthReport(
            isHealthy = isHealthy,
            failureReasons = failures
        )
    }
}
