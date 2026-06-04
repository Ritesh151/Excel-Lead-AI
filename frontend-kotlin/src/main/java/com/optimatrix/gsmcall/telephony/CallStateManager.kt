package com.optimatrix.gsmcall.telephony

import android.content.Context
import android.telephony.TelephonyManager
import com.optimatrix.gsmcall.utils.LogStore

/**
 * CallStateManager — Centralized call state tracking with history.
 *
 * Tracks:
 *   - Current raw telephony state (IDLE=0, RINGING=1, OFFHOOK=2)
 *   - Semantic phase (DIALING, RINGING, CONNECTED, ENDED)
 *   - Transition history for debugging
 *   - Outgoing vs incoming call detection
 *   - Connected duration tracking
 */
class CallStateManager(private val context: Context) {

    companion object {
        private const val TAG = "CallStateManager"
        const val STATE_IDLE = TelephonyManager.CALL_STATE_IDLE       // 0
        const val STATE_RINGING = TelephonyManager.CALL_STATE_RINGING  // 1
        const val STATE_OFFHOOK = TelephonyManager.CALL_STATE_OFFHOOK  // 2
    }

    data class StateTransition(
        val from: CallState,
        val to: CallState,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val transitions = mutableListOf<StateTransition>()
    private var currentState = CallState.IDLE
    private var connectedAt: Long? = null
    private var dialedAt: Long? = null

    val isConnected get() = currentState == CallState.OFFHOOK
    val isIdle get() = currentState == CallState.IDLE
    val isRinging get() = currentState == CallState.RINGING

    fun connectedDurationMs(): Long? {
        val ca = connectedAt ?: return null
        return System.currentTimeMillis() - ca
    }

    fun onStateChange(rawState: Int, phoneNumber: String?) {
        val newState = when (rawState) {
            STATE_IDLE -> CallState.IDLE
            STATE_RINGING -> CallState.RINGING
            STATE_OFFHOOK -> CallState.OFFHOOK
            else -> CallState.UNKNOWN
        }

        if (newState == currentState) return

        val transition = StateTransition(currentState, newState)
        transitions.add(transition)

        LogStore.log(TAG, "State: ${currentState.name} → ${newState.name} number=${phoneNumber}")

        when (newState) {
            CallState.OFFHOOK -> {
                if (connectedAt == null) connectedAt = System.currentTimeMillis()
            }
            CallState.IDLE -> {
                val duration = connectedAt?.let { System.currentTimeMillis() - it }
                LogStore.log(TAG, "Call ended — duration=${duration}ms transitions=${transitions.size}")
                connectedAt = null
                dialedAt = null
            }
            CallState.RINGING -> { /* customer phone is ringing on outbound call */ }
            else -> {}
        }

        currentState = newState
    }

    fun markDialed() {
        dialedAt = System.currentTimeMillis()
        LogStore.log(TAG, "Marked as dialed at $dialedAt")
    }

    fun getTransitionHistory(): List<String> = transitions.map {
        "[${it.timestamp % 100000}] ${it.from.name} → ${it.to.name}"
    }

    fun reset() {
        transitions.clear()
        currentState = CallState.IDLE
        connectedAt = null
        dialedAt = null
    }
}
