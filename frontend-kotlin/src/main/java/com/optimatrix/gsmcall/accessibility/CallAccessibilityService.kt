package com.optimatrix.gsmcall.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.optimatrix.gsmcall.services.CallAutomationService
import com.optimatrix.gsmcall.utils.LogStore

/**
 * CallAccessibilityService — Enhanced call UI monitor for Samsung SM-A176B.
 *
 * Responsibilities:
 *   1. Detect when Samsung InCallUI becomes active (call answered)
 *   2. Detect when call ends (InCallUI dismissed)
 *   3. Detect call answer button press for faster response time
 *   4. Provide timing synchronization to CallAutomationService
 *   5. Samsung One UI 6/7 package monitoring
 *
 * Samsung packages monitored:
 *   - com.samsung.android.incallui  (Samsung One UI dialer)
 *   - com.android.server.telecom    (Android telecom framework)
 *   - com.android.phone             (AOSP phone app)
 *   - com.samsung.android.dialer    (Samsung dialer)
 *
 * This service SUPPLEMENTS TelephonyController — it provides faster
 * call-answer detection via UI events instead of polling telephony state.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAccessibility"

        // Samsung InCallUI package names
        private val INCALL_PACKAGES = setOf(
            "com.samsung.android.incallui",
            "com.android.server.telecom",
            "com.android.phone",
            "com.samsung.android.dialer",
            "com.android.incallui"
        )

        // End-call button content descriptions (Samsung One UI various languages)
        private val END_CALL_DESCRIPTIONS = setOf(
            "end call", "end", "disconnect", "hang up",
            "कॉल समाप्त करें", "disconnect call", "call end"
        )

        // Answer button content descriptions
        private val ANSWER_DESCRIPTIONS = setOf(
            "answer", "accept", "answer call",
            "कॉल स्वीकार करें", "swipe to answer"
        )
    }

    private var inCallUiVisible = false
    private var lastCallEventTime = 0L

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    or AccessibilityEvent.TYPE_VIEW_CLICKED
                    or AccessibilityEvent.TYPE_ANNOUNCEMENT)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
            notificationTimeout = 50 // Fast polling
            packageNames = INCALL_PACKAGES.toTypedArray()
        }
        LogStore.log(TAG, "CallAccessibilityService connected — monitoring: $INCALL_PACKAGES")
    }

    // ── Event processing ──────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in INCALL_PACKAGES) return

        val eventType = event.eventType
        val className = event.className?.toString() ?: ""
        val now = System.currentTimeMillis()

        LogStore.log(TAG, "EVENT pkg=$pkg type=${eventTypeName(eventType)} class=${className.substringAfterLast('.')}")

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(pkg, className, now)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewClick(event, pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Scan for call state indicators in Samsung InCallUI
                if (pkg == "com.samsung.android.incallui") {
                    scanInCallUiContent(event)
                }
            }
        }
    }

    override fun onInterrupt() {
        LogStore.log(TAG, "Service interrupted")
    }

    // ── Window state tracking ─────────────────────────────────────────────────

    private fun handleWindowStateChange(pkg: String, className: String, now: Long) {
        val isCallActivity = className.contains("InCall", ignoreCase = true) ||
                className.contains("CallCard", ignoreCase = true) ||
                className.contains("DialerActivity", ignoreCase = true)

        if (pkg in INCALL_PACKAGES && isCallActivity) {
            if (!inCallUiVisible) {
                inCallUiVisible = true
                lastCallEventTime = now
                LogStore.log(TAG, "CALL UI APPEARED: pkg=$pkg class=$className")
                notifyCallUiVisible(pkg)
            }
        } else if (inCallUiVisible && now - lastCallEventTime > 500) {
            // Another window came to foreground — possibly call ended
            val otherPkg = pkg !in INCALL_PACKAGES
            if (otherPkg || !isCallActivity) {
                LogStore.log(TAG, "CALL UI DISMISSED — call likely ended")
                inCallUiVisible = false
                notifyCallEnded()
            }
        }
    }

    private fun handleViewClick(event: AccessibilityEvent, pkg: String) {
        val node = event.source ?: return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        when {
            desc in END_CALL_DESCRIPTIONS || text in END_CALL_DESCRIPTIONS -> {
                LogStore.log(TAG, "END CALL button pressed — pkg=$pkg desc=$desc")
                inCallUiVisible = false
                notifyCallEnded()
            }
            desc in ANSWER_DESCRIPTIONS || text in ANSWER_DESCRIPTIONS -> {
                LogStore.log(TAG, "ANSWER button pressed — pkg=$pkg desc=$desc")
                notifyCallAnswered()
            }
        }
    }

    private fun scanInCallUiContent(event: AccessibilityEvent) {
        // Look for "Active" or timer text indicating connected call
        try {
            val root = rootInActiveWindow ?: return
            scanNodeForCallState(root)
        } catch (ex: Exception) {
            // Non-fatal — accessibility tree may be unstable during transitions
        }
    }

    private fun scanNodeForCallState(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (text.contains("active") || text.contains("connected") || text.matches(Regex("\\d+:\\d+"))) {
            LogStore.log(TAG, "InCallUI state indicator: text='$text' desc='$desc'")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { scanNodeForCallState(it) }
        }
    }

    // ── Notifications to service ──────────────────────────────────────────────

    private fun notifyCallUiVisible(pkg: String) {
        broadcastCallEvent("incallui_visible", "InCallUI visible: $pkg")
    }

    private fun notifyCallAnswered() {
        broadcastCallEvent("call_answered_ui", "Call answer detected via Accessibility")
    }

    private fun notifyCallEnded() {
        broadcastCallEvent("call_ended_ui", "Call end detected via Accessibility")
    }

    private fun broadcastCallEvent(event: String, message: String) {
        LogStore.log(TAG, "Broadcasting: $event — $message")
        sendBroadcast(Intent(CallAutomationService.ACTION_STATUS_UPDATE).also {
            it.putExtra(CallAutomationService.EXTRA_CURRENT_STATUS, event)
            it.putExtra("accessibility_event", true)
        })
        sendBroadcast(Intent(CallAutomationService.ACTION_LOG_UPDATE).also {
            it.putExtra(CallAutomationService.EXTRA_LOG_MESSAGE, "[Accessibility] $message")
        })
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
        else -> "OTHER($type)"
    }
}
