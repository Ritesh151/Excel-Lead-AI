package com.optimatrix.gsmcall.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.optimatrix.gsmcall.api.ApiClient
import com.optimatrix.gsmcall.utils.LogStore
import java.io.File

class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val recordingPath = inputData.getString(KEY_RECORDING_PATH) ?: return Result.failure()
        val file = File(recordingPath)
        if (!file.exists()) return Result.failure()

        val apiClient = ApiClient(applicationContext)
        val response = apiClient.uploadRecording(file, com.optimatrix.gsmcall.telephony.CallSession(outgoing = true, remoteNumber = null))
        LogStore.log("SyncWorker", "Upload attempt for ${file.name}: ${response.intent}")
        return if (response.intent != ApiClient.CallerIntent.UNKNOWN) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_RECORDING_PATH = "key_recording_path"
    }
}
