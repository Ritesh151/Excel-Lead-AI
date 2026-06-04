package com.optimatrix.gsmcall.telephony

data class CallSession(
    val outgoing: Boolean,
    val remoteNumber: String?,
    val phase: Phase = Phase.IDLE,
) {
    enum class Phase {
        IDLE,
        RINGING,
        DIALING,
        CONNECTED,
        ENDED
    }
}
