package com.optimatrix.gsmcall.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.optimatrix.gsmcall.utils.LogStore

/**
 * TelephonyController — Complete telephony state monitoring.
 *
 * Uses:
 *   - PhoneStateListener (TelephonyManager)
 *   - BroadcastReceiver for ACTION_NEW_OUTGOING_CALL
 *   - CallStateManager for history and derived state
 *
 * Android 12+ note: PhoneStateListener is deprecated in favor of
 * TelephonyCallback. We use PhoneStateListener for max compatibility
 * (API 26+). Samsung SM-A176B is Android 14 so this works fine.
 *
 * UPGRADED: Robust state machine with Samsung-specific timing handling.
 * Added per-state logging with timestamps.
 */
class TelephonyController(
    private val context: Context,
    private val listener: CallSessionTracker.Listener,
) {
    companion object {
        private const val TAG = "TelephonyController"
        // Samsung One UI: OFFHOOK comes fast on outgoing, we add small delay before CONNECTED
        private const val OUTGOING_CONNECT_DELAY_MS = 1200L
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val sessionTracker = CallSessionTracker(listener)
    private val callStateManager = CallStateManager(context)
    private var outgoingReceiver: BroadcastReceiver? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startListening() {
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        registerOutgoingReceiver()
        LogStore.log(TAG, "Started listening to telephony state")
    }

    fun stopListening() {
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        unregisterOutgoingReceiver()
        LogStore.log(TAG, "Stopped listening")
    }

    // ── Call control ──────────────────────────────────────────────────────────

    fun endCall(): Boolean {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                telecom.endCall()
            } else {
                false
            }
            LogStore.log(TAG, "endCall() result=$result")
            result
        } catch (ex: Exception) {
            LogStore.log(TAG, "endCall() exception: ${ex.message}")
            false
        }
    }

    // ── PhoneStateListener ────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val stateName = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> "IDLE(0)"
                TelephonyManager.CALL_STATE_RINGING -> "RINGING(1)"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK(2)"
                else -> "UNKNOWN($state)"
            }
            LogStore.log(TAG, "onCallStateChanged: $stateName number=${phoneNumber?.take(6)}***")

            callStateManager.onStateChange(state, phoneNumber)

            val callState = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> CallState.UNKNOWN
            }

            sessionTracker.updateState(callState, phoneNumber)
        }
    }

    // ── Outgoing call receiver ────────────────────────────────────────────────

    private fun registerOutgoingReceiver() {
        if (outgoingReceiver != null) return
        outgoingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                    val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                    LogStore.log(TAG, "ACTION_NEW_OUTGOING_CALL number=${number?.take(6)}***")
                    callStateManager.markDialed()
                    sessionTracker.markOutgoing()
                }
            }
        }
        try {
            context.registerReceiver(
                outgoingReceiver,
                IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL)
            )
        } catch (ex: Exception) {
            LogStore.log(TAG, "registerOutgoingReceiver exception: ${ex.message}")
        }
    }

    private fun unregisterOutgoingReceiver() {
        outgoingReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            outgoingReceiver = null
        }
    }

    // ── Diagnostic ────────────────────────────────────────────────────────────

    fun getStateHistory(): List<String> = callStateManager.getTransitionHistory()
}
