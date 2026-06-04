package com.optimatrix.gsmcall.telephony

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class CallSessionTracker(private val listener: Listener) {
    interface Listener {
        fun onSessionUpdated(session: CallSession)
    }

    private val connectGuard = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentSession = CallSession(outgoing = false, remoteNumber = null)

    fun updateState(rawState: CallState, incomingNumber: String?) {
        val previous = currentSession.phase
        currentSession = when (rawState) {
            CallState.IDLE -> CallSession(outgoing = currentSession.outgoing, remoteNumber = incomingNumber, phase = CallSession.Phase.ENDED)
            CallState.RINGING -> CallSession(outgoing = false, remoteNumber = incomingNumber, phase = CallSession.Phase.RINGING)
            CallState.OFFHOOK -> currentSession.copy(outgoing = currentSession.outgoing || false, remoteNumber = incomingNumber, phase = if (previous == CallSession.Phase.RINGING) CallSession.Phase.CONNECTED else CallSession.Phase.DIALING)
            CallState.UNKNOWN -> currentSession
        }
        notifySession(currentSession)
        if (currentSession.phase == CallSession.Phase.DIALING && !connectGuard.get()) {
            connectGuard.set(true)
            uiHandler.postDelayed({
                if (currentSession.phase == CallSession.Phase.DIALING) {
                    currentSession = currentSession.copy(phase = CallSession.Phase.CONNECTED)
                    notifySession(currentSession)
                }
            }, 1400L)
        }
    }

    fun markOutgoing() {
        currentSession = currentSession.copy(outgoing = true)
        notifySession(currentSession)
    }

    private fun notifySession(session: CallSession) {
        listener.onSessionUpdated(session)
    }
}
