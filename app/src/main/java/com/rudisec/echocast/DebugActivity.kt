package com.rudisec.echocast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Diagnostics screen. Built in code on purpose: no layout to keep in sync.
 *
 * Open it from the app by long-pressing the logo, or with:
 *   adb shell am start -n com.rudisec.echocast/.DebugActivity
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var diagView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val refresh = Button(this).apply {
            text = getString(R.string.debug_refresh)
            setOnClickListener { update() }
        }
        val copy = Button(this).apply {
            text = getString(R.string.debug_copy)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("EchoCast debug", fullText()))
                Toast.makeText(this@DebugActivity, getString(R.string.debug_copied), Toast.LENGTH_SHORT).show()
            }
        }
        val save = Button(this).apply {
            text = getString(R.string.debug_save)
            setOnClickListener {
                val file = DebugLog.saveToFile(this@DebugActivity)
                Toast.makeText(this@DebugActivity, file.absolutePath, Toast.LENGTH_LONG).show()
            }
        }
        val clear = Button(this).apply {
            text = getString(R.string.debug_clear)
            setOnClickListener {
                DebugLog.clear()
                update()
            }
        }
        val toggleVerbose = Button(this).apply {
            text = getString(R.string.debug_toggle_verbose)
            setOnClickListener {
                DebugLog.verbose = !DebugLog.verbose
                Preferences(this@DebugActivity).verboseLogging = DebugLog.verbose
                update()
            }
        }
        val toggleIncallMusic = Button(this).apply {
            text = getString(R.string.debug_toggle_incall_music)
            setOnClickListener {
                val prefs = Preferences(this@DebugActivity)
                prefs.experimentalIncallMusic = !prefs.experimentalIncallMusic
                update()
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refresh, rowParams())
            addView(copy, rowParams())
            addView(save, rowParams())
        }
        val buttons2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clear, rowParams())
            addView(toggleVerbose, rowParams())
        }
        val buttons3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggleIncallMusic, rowParams())
        }

        diagView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }

        root.addView(buttons)
        root.addView(buttons2)
        root.addView(buttons3)
        root.addView(diagView)
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
            )
            addView(logView)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        setTitle(R.string.debug_title)
        update()
    }

    private fun update() {
        diagView.text = TelephonyAudio.diagnostics(this) +
                "\nverbose logging: ${DebugLog.verbose}\n" +
                "experimental incall_music: ${Preferences(this).experimentalIncallMusic}\n"
        logView.text = DebugLog.dump().ifBlank { "(no log entries yet)" }
        logView.post {
            (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun fullText(): String =
        diagView.text.toString() + "\n\n--- log ---\n" + DebugLog.dump()

    private fun rowParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(4)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
