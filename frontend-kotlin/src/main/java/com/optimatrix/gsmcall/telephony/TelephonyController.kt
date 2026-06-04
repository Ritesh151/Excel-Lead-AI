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

class TelephonyController(
    private val context: Context,
    private val tracker: CallSessionTracker.Listener,
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val sessionTracker = CallSessionTracker(tracker)
    private var outgoingReceiver: BroadcastReceiver? = null

    fun startListening() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        registerOutgoingReceiver()
        LogStore.log("TelephonyController", "Started listening to call state")
    }

    fun stopListening() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        unregisterOutgoingReceiver()
        LogStore.log("TelephonyController", "Stopped listening to call state")
    }

    fun endCall(): Boolean {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
            } else {
                false
            }
            LogStore.log("TelephonyController", "Attempt end call result=$success")
            success
        } catch (ex: Exception) {
            LogStore.log("TelephonyController", "End call failed: ${ex.message}")
            false
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val callState = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> CallState.UNKNOWN
            }
            LogStore.log("TelephonyController", "CallState changed: $callState number=$phoneNumber")
            sessionTracker.updateState(callState, phoneNumber)
        }
    }

    private fun registerOutgoingReceiver() {
        if (outgoingReceiver != null) return
        outgoingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                    val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                    LogStore.log("TelephonyController", "Outgoing call detected: $number")
                    sessionTracker.markOutgoing()
                }
            }
        }
        context.registerReceiver(outgoingReceiver, IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL))
    }

    private fun unregisterOutgoingReceiver() {
        outgoingReceiver?.let {
            context.unregisterReceiver(it)
            outgoingReceiver = null
        }
    }
}
