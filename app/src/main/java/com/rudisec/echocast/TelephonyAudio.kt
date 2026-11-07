package com.rudisec.echocast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager

/**
 * Everything needed to find out *why* in-call playback does or does not work.
 *
 * The short version: Android only lets an app push PCM into the cellular uplink
 * if the platform exposes an output device of type TYPE_TELEPHONY. Pixels do,
 * some Samsung/OnePlus builds do, many others simply don't — and no app-side
 * trick can invent it.
 */
object TelephonyAudio {
    private const val TAG = "Telephony"

    fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The device the other party hears us through, if the platform exposes it. */
    fun findTelephonyOutput(context: Context): AudioDeviceInfo? =
        runCatching {
            audioManager(context)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        }.getOrNull()

    /**
     * MODE_IN_CALL is set by the telephony stack during a cellular call.
     * MODE_IN_COMMUNICATION is used by VoIP apps and is not enough.
     */
    fun isCellularCallActive(context: Context): Boolean =
        runCatching { audioManager(context).mode == AudioManager.MODE_IN_CALL }.getOrDefault(false)

    /**
     * Some Qualcomm HALs mix an "incall music" stream into the uplink when the
     * vendor parameter is enabled. Pure guesswork, exposed as an experiment.
     */
    fun setIncallMusic(context: Context, enabled: Boolean): Boolean {
        val am = audioManager(context)
        return runCatching {
            am.setParameters("incall_music_enabled=${if (enabled) "true" else "false"}")
            true
        }.getOrElse {
            DebugLog.w(TAG, "setParameters incall_music_enabled failed: ${it.message}")
            false
        }
    }

    fun isDefaultDialer(context: Context): Boolean = runCatching {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.defaultDialerPackage == context.packageName
    }.getOrDefault(false)

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun isSystemApp(context: Context): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
        (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        else -> "type($type)"
    }

    fun describeOutputs(context: Context): String {
        val devices = runCatching {
            audioManager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse { return "getDevices failed: ${it.message}" }
        if (devices.isEmpty()) return "(no output devices reported)"
        return devices.joinToString("\n") { d ->
            "  ${typeName(d.type)} id=${d.id} name=${d.productName}"
        }
    }

    /** Human readable report shown in the diagnostics screen. */
    fun diagnostics(context: Context): String {
        val am = audioManager(context)
        val telephony = findTelephonyOutput(context)
        val sb = StringBuilder()

        sb.appendLine("MODE NOW: ${modeName(am.mode)}")
        sb.appendLine("cellular call active: ${isCellularCallActive(context)}")
        sb.appendLine("EchoCast InCallService saw a call: ${EchoCastInCallService.activeCall} (last state: ${EchoCastInCallService.lastState})")
        sb.appendLine()
        sb.appendLine("TYPE_TELEPHONY output: " + (telephony?.let { "FOUND (id=${it.id}, name=${it.productName})" }
            ?: "NOT AVAILABLE  <-- in-call playback cannot work"))
        sb.appendLine()
        sb.appendLine("system app: ${isSystemApp(context)}")
        sb.appendLine("default phone app: ${isDefaultDialer(context)}")
        sb.appendLine("MODIFY_PHONE_STATE: ${granted(context, Manifest.permission.MODIFY_PHONE_STATE)}")
        sb.appendLine("CONTROL_INCALL_EXPERIENCE: ${granted(context, "android.permission.CONTROL_INCALL_EXPERIENCE")}")
        sb.appendLine("RECORD_AUDIO: ${granted(context, Manifest.permission.RECORD_AUDIO)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.appendLine("POST_NOTIFICATIONS: ${granted(context, Manifest.permission.POST_NOTIFICATIONS)}")
        }
        sb.appendLine()
        sb.appendLine("output devices:")
        sb.appendLine(describeOutputs(context))
        sb.appendLine()
        sb.appendLine("prefs: enabled=${Preferences(context).isEnabled} mode=${Preferences(context).playMode} " +
                "experimentalIncallMusic=${Preferences(context).experimentalIncallMusic}")
        sb.appendLine("last playback error: ${PlaybackService.lastError ?: "none"}")
        return sb.toString()
    }

    private fun granted(context: Context, permission: String): String = when {
        hasPermission(context, permission) -> "GRANTED"
        else -> "DENIED"
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "mode($mode)"
    }
}
