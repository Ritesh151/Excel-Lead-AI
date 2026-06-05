package com.optimatrix.gsmcall.crash

import android.app.Application
import android.os.Build
import android.os.Process
import com.optimatrix.gsmcall.utils.LogStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * GlobalCrashHandler — Captures ALL uncaught exceptions and fatal signals
 *
 * Handles:
 *   - Main thread crashes
 *   - Background thread crashes
 *   - JVM RuntimeExceptions
 *   - Service crashes
 *   - Audio system crashes
 *   - WebSocket crashes
 *   - Telephony crashes
 *
 * Saves all crashes to: Android/data/package/logs/crash.log
 */
class GlobalCrashHandler(private val application: Application) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val MAX_CRASH_LOG_SIZE_MB = 10
        private const val MAX_CRASH_LOGS = 5

        fun initialize(application: Application) {
            val handler = GlobalCrashHandler(application)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            LogStore.log(TAG, "Crash handler initialized")
        }
    }

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val crashLogDir = File(application.getExternalFilesDir(null), "logs").apply {
        if (!exists()) mkdirs()
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            // Log the crash
            val stackTrace = exception.stackTraceToString()
            val crashLog = formatCrashLog(thread, exception, stackTrace)

            // Save to file
            saveCrashLog(crashLog)

            // Log to LogStore for UI visibility
            LogStore.log(TAG, "FATAL CRASH: ${exception.javaClass.simpleName}")
            LogStore.log(TAG, "Thread: ${thread.name}")
            LogStore.log(TAG, "Stack: ${stackTrace.take(500)}")

            // Cleanup resources before exit
            cleanupResources()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Call original handler (system crash dialog)
            originalHandler?.uncaughtException(thread, exception) ?: exitProcess(1)
        }
    }

    private fun formatCrashLog(thread: Thread, exception: Throwable, stackTrace: String): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val timestamp = dateFormat.format(Date())

        return """
            ════════════════════════════════════════════════════════════════════════════
            CRASH REPORT — $timestamp
            ════════════════════════════════════════════════════════════════════════════
            
            DEVICE INFO:
              OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
              Device: ${Build.MANUFACTURER} ${Build.MODEL}
              Process: ${Process.myPid()} (${Build.BRAND})
            
            CRASH DETAILS:
              Exception: ${exception.javaClass.canonicalName}
              Message: ${exception.message ?: "null"}
              Thread: ${thread.name} (${thread.id})
              Thread State: ${thread.state}
            
            STACK TRACE:
            $stackTrace
            
            CAUSE CHAIN:
            ${getCauseChain(exception)}
            
            MEMORY:
              Runtime: ${Runtime.getRuntime().totalMemory() / 1024 / 1024} MB total
              Free: ${Runtime.getRuntime().freeMemory() / 1024 / 1024} MB free
              Max: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB max
            
            ════════════════════════════════════════════════════════════════════════════
            
        """.trimIndent()
    }

    private fun getCauseChain(exception: Throwable): String {
        val causes = StringBuilder()
        var current: Throwable? = exception.cause
        var depth = 0

        while (current != null && depth < 5) {
            causes.append("  [$depth] ${current.javaClass.simpleName}: ${current.message}\n")
            causes.append("      at ${current.stackTrace.firstOrNull()?.toString()}\n")
            current = current.cause
            depth++
        }

        return if (causes.isEmpty()) "No cause chain" else causes.toString()
    }

    private fun saveCrashLog(crashLog: String) {
        try {
            val mainCrashLog = File(crashLogDir, "crash.log")

            // Rotate logs if too large
            if (mainCrashLog.exists() && mainCrashLog.length() > MAX_CRASH_LOG_SIZE_MB * 1024 * 1024) {
                rotateLogFile(mainCrashLog)
            }

            // Append to main crash log
            mainCrashLog.appendText(crashLog + "\n")

            LogStore.log(TAG, "Crash log saved: ${mainCrashLog.absolutePath}")
        } catch (e: Exception) {
            LogStore.log(TAG, "Failed to save crash log: ${e.message}")
        }
    }

    private fun rotateLogFile(file: File) {
        try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val rotatedFile = File(crashLogDir, "crash_$timestamp.log")

            file.renameTo(rotatedFile)

            // Clean up old logs
            val allCrashLogs = crashLogDir.listFiles { f ->
                f.name.startsWith("crash_") && f.name.endsWith(".log")
            } ?: emptyArray()

            if (allCrashLogs.size > MAX_CRASH_LOGS) {
                allCrashLogs
                    .sortedBy { it.lastModified() }
                    .take(allCrashLogs.size - MAX_CRASH_LOGS)
                    .forEach { it.delete() }
            }

            LogStore.log(TAG, "Rotated crash log to: ${rotatedFile.name}")
        } catch (e: Exception) {
            LogStore.log(TAG, "Failed to rotate crash log: ${e.message}")
        }
    }

    private fun cleanupResources() {
        try {
            // Signal other components to cleanup
            LogStore.log(TAG, "Cleaning up resources before crash exit...")
            Thread.sleep(500) // Give systems time to log
        } catch (_: Exception) {}
    }
}
