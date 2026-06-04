package com.optimatrix.gsmcall.api

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.optimatrix.gsmcall.telephony.CallSession
import com.optimatrix.gsmcall.utils.LogStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "http://your-backend-host:8000"
        private const val RECORDING_ENDPOINT = "/api/calls/recording"
    }

    enum class CallerIntent { YES, NO, UNKNOWN }

    data class UploadResult(val intent: CallerIntent, val transcription: String?)

    private val moshi = Moshi.Builder().build()
    private val responseAdapter: JsonAdapter<IntentResponse> = moshi.adapter(IntentResponse::class.java)

    fun uploadRecording(recording: File, session: CallSession): UploadResult {
        return try {
            val fileBody = recording.asRequestBody("audio/wav".toMediaTypeOrNull())
            val formData = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", recording.name, fileBody)
                .addFormDataPart("callType", if (session.outgoing) "outgoing" else "incoming")
                .addFormDataPart("remoteNumber", session.remoteNumber ?: "unknown")
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder()
                .url(BASE_URL + RECORDING_ENDPOINT)
                .post(formData)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogStore.log("ApiClient", "Upload failed status=${response.code}")
                    return UploadResult(CallerIntent.UNKNOWN, null)
                }
                val body = response.body?.string().orEmpty()
                val parsed = responseAdapter.fromJson(body)
                val intent = when (parsed?.intent?.lowercase()) {
                    "yes" -> CallerIntent.YES
                    "no" -> CallerIntent.NO
                    else -> CallerIntent.UNKNOWN
                }
                LogStore.log("ApiClient", "Upload succeeded intent=${parsed?.intent}")
                UploadResult(intent, parsed?.transcription)
            }
        } catch (ex: Exception) {
            LogStore.log("ApiClient", "Upload exception: ${ex.message}")
            UploadResult(CallerIntent.UNKNOWN, null)
        }
    }

    data class IntentResponse(val intent: String?, val transcription: String?)
}
