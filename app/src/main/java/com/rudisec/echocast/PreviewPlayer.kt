package com.rudisec.echocast

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Normal speaker playback, used for previewing a sound when no call is active
 * (or when the platform does not expose a telephony output at all).
 */
class PreviewPlayer(private val context: Context) {

    private companion object {
        const val TAG = "Preview"
    }

    private var player: MediaPlayer? = null

    fun play(item: AudioItem, volume: Float, onFinished: (error: String?) -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(context, item.uri)
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener {
                DebugLog.i(TAG, "preview completed '${item.name}'")
                onFinished(null)
            }
            mp.setOnErrorListener { _, what, extra ->
                val msg = "MediaPlayer error what=$what extra=$extra"
                DebugLog.e(TAG, msg)
                onFinished(msg)
                true
            }
            mp.prepare()
            mp.start()
            DebugLog.i(TAG, "preview started '${item.name}'")
        } catch (e: Exception) {
            DebugLog.e(TAG, "preview failed '${item.name}'", e)
            runCatching { mp.release() }
            if (player === mp) player = null
            onFinished("Preview failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    fun resume() {
        runCatching { player?.start() }
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }
}
