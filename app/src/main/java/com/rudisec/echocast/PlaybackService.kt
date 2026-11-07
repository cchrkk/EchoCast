package com.rudisec.echocast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Owns playback. Replaces the playback half of the old InCallService, so that
 * sounds can be played even when EchoCast is not the system's default phone app
 * (an InCallService is only bound for the default dialer).
 *
 * Routing decision, per item:
 *   1. TYPE_TELEPHONY output exists  -> AudioTrack straight into the call uplink
 *   2. experimental incall_music     -> vendor mixer parameter (Qualcomm only)
 *   3. otherwise                     -> normal speaker playback (preview)
 */
class PlaybackService : Service(), SingleAudioPlayer.AudioPlayerListener {

    companion object {
        private const val TAG = "Playback"

        const val ACTION_PLAY = "com.rudisec.echocast.PLAY_AUDIO"
        const val ACTION_START_SEQUENCE = "com.rudisec.echocast.START_SHUFFLE"
        const val ACTION_STOP = "com.rudisec.echocast.STOP_AUDIO"
        const val ACTION_PAUSE = "com.rudisec.echocast.PAUSE"
        const val ACTION_RESUME = "com.rudisec.echocast.RESUME"

        const val EXTRA_AUDIO_ID = "audio_id"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_AUDIO_NAME = "audio_name"
        const val EXTRA_PLAY_MODE = "play_mode"

        const val ACTION_STATE = "com.rudisec.echocast.PLAYBACK_STATE"
        const val ACTION_ERROR = "com.rudisec.echocast.PLAYBACK_ERROR"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_MESSAGE = "message"

        const val NOTIFICATION_ID = 101

        @Volatile
        var lastError: String? = null
            private set

        fun play(context: Context, item: AudioItem, mode: String) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_AUDIO_ID, item.id)
                putExtra(EXTRA_AUDIO_URI, item.uri.toString())
                putExtra(EXTRA_AUDIO_NAME, item.name)
                putExtra(EXTRA_PLAY_MODE, mode)
            }
            start(context, intent)
        }

        fun startSequence(context: Context) {
            start(context, Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START_SEQUENCE
            })
        }

        /** Background-start restrictions can throw; never let that crash a caller. */
        private fun start(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                DebugLog.e(TAG, "startForegroundService failed, trying startService", e)
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun pause(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            runCatching { context.startService(intent) }
        }

        fun resume(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_RESUME
            }
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var prefs: Preferences
    private lateinit var playlist: AudioPlaylist
    private val handler = Handler(Looper.getMainLooper())

    private var queue: MutableList<AudioItem> = mutableListOf()
    private var index = 0
    private var mode = "single"
    private var loopEnabled = false
    private var shuffle = false
    private var playing = false

    private var telephonyPlayer: SingleAudioPlayer? = null
    private var previewPlayer: PreviewPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        playlist = AudioPlaylist(this)
        prefs.isPaused = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopEverything()
            return START_NOT_STICKY
        }
        DebugLog.d(TAG, "action=${intent.action}")

        when (intent.action) {
            ACTION_PLAY -> startForegroundSafely()
            ACTION_START_SEQUENCE -> startForegroundSafely()
            else -> Unit
        }

        when (intent.action) {
            ACTION_PLAY -> {
                val uri = intent.getStringExtra(EXTRA_AUDIO_URI)
                if (uri == null) {
                    fail("No audio uri supplied")
                    return START_NOT_STICKY
                }
                val item = AudioItem(
                    id = intent.getStringExtra(EXTRA_AUDIO_ID) ?: uri.hashCode().toString(),
                    name = intent.getStringExtra(EXTRA_AUDIO_NAME) ?: "audio",
                    uri = Uri.parse(uri)
                )
                buildQueueForItem(item, intent.getStringExtra(EXTRA_PLAY_MODE))
                index = 0
                loopEnabled = mode == "loop"
                shuffle = mode == "shuffle"
                playCurrent()
            }

            ACTION_START_SEQUENCE -> {
                playlist.reload()
                val items = playlist.getEnabledItems().shuffled().toMutableList()
                if (items.isEmpty()) {
                    fail("No audio files in the soundboard")
                    return START_NOT_STICKY
                }
                queue = items
                index = 0
                mode = "shuffle"
                shuffle = true
                loopEnabled = prefs.isShuffleLoop
                prefs.isShufflePaused = false
                playCurrent()
            }

            ACTION_STOP -> stopEverything()
            ACTION_PAUSE -> {
                prefs.isPaused = true
                prefs.isShufflePaused = true
                telephonyPlayer?.setPaused(true)
                previewPlayer?.pause()
            }
            ACTION_RESUME -> {
                prefs.isPaused = false
                prefs.isShufflePaused = false
                telephonyPlayer?.setPaused(false)
                previewPlayer?.resume()
            }
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------- queue

    private fun buildQueueForItem(item: AudioItem, requestedMode: String?) {
        mode = requestedMode ?: prefs.playMode
        queue = when (mode) {
            "shuffle" -> {
                playlist.reload()
                val enabled = playlist.getEnabledItems().toMutableList()
                if (enabled.none { it.id == item.id }) enabled.add(item)
                enabled.shuffled().toMutableList()
            }
            else -> mutableListOf(item)
        }
    }

    private fun playCurrent() {
        if (index >= queue.size) {
            if (loopEnabled && queue.isNotEmpty()) {
                if (shuffle) queue.shuffle()
                index = 0
            } else {
                DebugLog.i(TAG, "sequence finished")
                stopEverything()
                return
            }
        }

        val item = queue[index]
        stopCurrent()

        val telephony = TelephonyAudio.findTelephonyOutput(this)
        val cellularCall = TelephonyAudio.isCellularCallActive(this)

        when {
            telephony != null -> {
                playing = true
                broadcastState(item.id, "telephony")
                telephonyPlayer = SingleAudioPlayer(this, this, item, telephony).also { it.start() }
            }

            prefs.experimentalIncallMusic && cellularCall -> {
                DebugLog.w(TAG, "no TYPE_TELEPHONY; trying experimental incall_music routing")
                TelephonyAudio.setIncallMusic(this, true)
                playing = true
                broadcastState(item.id, "incall_music")
                telephonyPlayer = SingleAudioPlayer(this, this, item, null, useMediaUsage = true).also { it.start() }
            }

            else -> {
                if (cellularCall) {
                    DebugLog.w(TAG, "cellular call active but no telephony output -> speaker preview")
                }
                playing = true
                broadcastState(item.id, "preview")
                val p = PreviewPlayer(this)
                previewPlayer = p
                p.play(item, prefs.volume) { error ->
                    handler.post { onPreviewFinished(error) }
                }
            }
        }
    }

    private fun onPreviewFinished(error: String?) {
        if (error != null) {
            fail(error)
            return
        }
        advance()
    }

    private fun advance() {
        if (!playing) return
        index++
        playCurrent()
    }

    private fun stopCurrent() {
        telephonyPlayer?.let {
            it.cancel()
            runCatching { it.join(500) }
        }
        telephonyPlayer = null
        previewPlayer?.stop()
        previewPlayer = null
        if (prefs.experimentalIncallMusic) {
            TelephonyAudio.setIncallMusic(this, false)
        }
    }

    private fun stopEverything() {
        playing = false
        stopCurrent()
        broadcastState(null, "stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------- player callbacks

    override fun onPlaybackCompleted() {
        handler.post {
            if (!playing) return@post
            if (mode == "single") {
                // one shot: pressing play on one sound should not start a queue
                stopEverything()
            } else {
                advance()
            }
        }
    }

    override fun onPlaybackFailed(errorMsg: String?) {
        handler.post { fail(errorMsg ?: "Unknown playback error") }
    }

    private fun fail(message: String) {
        lastError = message
        DebugLog.e(TAG, "failure: $message")
        sendBroadcast(Intent(ACTION_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        })
        playing = false
        stopCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastState(itemId: String?, route: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PLAYING, playing)
            putExtra(EXTRA_ITEM_ID, itemId ?: "")
            putExtra(EXTRA_ROUTE, route)
        })
    }

    // ------------------------------------------------------------ foreground

    private fun startForegroundSafely() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            DebugLog.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, EchoCastApplication.CHANNEL_ID_PERSISTENT)
            .setContentTitle(getString(R.string.notification_playback_in_progress))
            .setContentText(getString(R.string.notification_playback_route, currentRouteName()))
            .setSmallIcon(R.drawable.ic_launcher_quick_settings)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun currentRouteName(): String =
        if (TelephonyAudio.findTelephonyOutput(this) != null) "call" else "speaker"

    override fun onDestroy() {
        playing = false
        stopCurrent()
        super.onDestroy()
    }
}
