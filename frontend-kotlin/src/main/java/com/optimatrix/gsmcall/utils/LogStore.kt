package com.optimatrix.gsmcall.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private val entries = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp [$tag] $message"
        synchronized(entries) {
            entries.add(0, entry)
            if (entries.size > 500) {
                entries.removeLast()
            }
        }
    }

    fun snapshot(): List<String> {
        return synchronized(entries) { entries.toList() }
    }

    fun exportLogs(context: Context): File? {
        return try {
            val logsDir = File(context.getExternalFilesDir(null), "logs").apply {
                if (!exists()) mkdirs()
            }
            val output = File(logsDir, "call_automation_${System.currentTimeMillis()}.log")
            FileWriter(output).use { writer ->
                snapshot().forEach { writer.appendLine(it) }
            }
            output
        } catch (ex: Exception) {
            null
        }
    }
}
