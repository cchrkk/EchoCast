package com.rudisec.echocast

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory + on-disk log used by the diagnostics screen.
 *
 * Everything that happens on the audio path is written here so the user can
 * grab a log without a PC (`adb logcat` still works, tag is `EchoCast/...`).
 */
object DebugLog {
    private const val MAX_LINES = 1000
    private const val TAG = "EchoCast"

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** When false only W/E are recorded. */
    @Volatile
    var verbose: Boolean = true

    fun d(tag: String, msg: String) {
        if (verbose) add("D", tag, msg)
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)

    fun w(tag: String, msg: String) = add("W", tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        add("E", tag, msg)
        if (t != null) {
            add("E", tag, "${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.firstOrNull()?.let { add("E", tag, "  at $it") }
        }
        Log.e("$TAG/$tag", msg, t)
    }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        when (level) {
            "D" -> Log.d("$TAG/$tag", msg)
            "I" -> Log.i("$TAG/$tag", msg)
            "W" -> Log.w("$TAG/$tag", msg)
            else -> if (msg.startsWith("  at")) Log.e("$TAG/$tag", msg) else Unit
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }

    /** Writes the log to the app's external files dir and returns the file. */
    fun saveToFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, "echocast-debug.log")
        f.writeText(header(context) + "\n\n" + dump())
        return f
    }

    fun header(context: Context): String = buildString {
        appendLine("EchoCast debug log")
        appendLine("generated: ${Date()}")
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("package: ${context.packageName}")
    }
}
