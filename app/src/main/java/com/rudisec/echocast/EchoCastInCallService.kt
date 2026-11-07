package com.rudisec.echocast

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService

/**
 * Call observer only.
 *
 * Android binds an InCallService exclusively for the *default phone app*, so if
 * EchoCast is not set as default dialer this class never receives callbacks.
 * Playback therefore lives in [PlaybackService] and does not depend on this
 * class; here we only record state for the diagnostics screen and stop the
 * sound when the call ends.
 */
class EchoCastInCallService : InCallService() {

    companion object {
        private const val TAG = "InCall"

        @Volatile
        var activeCall: Boolean = false
            private set

        @Volatile
        var lastState: String = "idle"
            private set

        @Volatile
        var lastNumber: String? = null
            private set

        fun stateName(state: Int): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
            Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
            else -> "state($state)"
        }
    }

    private val calls = HashMap<String, Call>()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            DebugLog.i(TAG, "state ${stateName(state)}")
            lastState = stateName(state)
            handleState(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            lastNumber = details.handle?.schemeSpecificPart
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        DebugLog.i(TAG, "call added")
        call.registerCallback(callback)
        calls[call.toString()] = call
        activeCall = true
        lastNumber = call.details?.handle?.schemeSpecificPart
        DebugLog.i(TAG, "number=${lastNumber ?: "unknown"}")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        DebugLog.i(TAG, "call removed")
        call.unregisterCallback(callback)
        calls.remove(call.toString())
        if (calls.isEmpty()) {
            activeCall = false
            lastState = "idle"
            PlaybackService.stop(this)
        }
    }

    private fun handleState(call: Call, state: Int) {
        val disconnected = state == Call.STATE_DISCONNECTED ||
                state == Call.STATE_DISCONNECTING
        if (disconnected) {
            calls.remove(call.toString())
            if (calls.isEmpty()) {
                activeCall = false
                PlaybackService.stop(this)
            }
        }
    }
}
