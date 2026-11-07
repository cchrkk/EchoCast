package com.rudisec.echocast

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams one audio file into a chosen output device.
 *
 * Used for two routes:
 *  - telephony (AudioDeviceInfo.TYPE_TELEPHONY) => the other party hears it
 *  - local speaker (preferredDevice == null, usage = MEDIA) => preview only
 *
 * The previous implementation created the AudioTrack from the *compressed*
 * input format and ignored INFO_OUTPUT_FORMAT_CHANGED, which produced
 * "playback failed" on any file whose decoder changed the channel count or
 * sample rate (most MP3/AAC files). It now waits for the real decoded format.
 */
class SingleAudioPlayer(
    private val context: Context,
    private val listener: AudioPlayerListener,
    private val audioItem: AudioItem,
    private val preferredDevice: AudioDeviceInfo?,
    private val useMediaUsage: Boolean = false
) : Thread("EchoCastPlayer") {

    interface AudioPlayerListener {
        fun onPlaybackCompleted()
        fun onPlaybackFailed(errorMsg: String?)
    }

    companion object {
        private const val TAG = "Player"
        private const val TIMEOUT_US = 10_000L
    }

    @Volatile private var cancelled = false
    @Volatile private var paused = false
    private var errorMsg: String? = null
    private var currentTrack: AudioTrack? = null

    private val started = AtomicBoolean(false)

    fun cancel() {
        cancelled = true
        runCatching { currentTrack?.pause() }
    }

    fun setPaused(value: Boolean) {
        paused = value
        val t = currentTrack ?: return
        runCatching {
            if (value) t.pause() else t.play()
        }
    }

    val routeLabel: String
        get() = if (preferredDevice != null) "telephony" else "local"

    override fun run() {
        if (!started.compareAndSet(false, true)) return
        DebugLog.i(TAG, "start '${audioItem.name}' route=$routeLabel device=${preferredDevice?.productName}")
        try {
            playOnce()
        } catch (e: Exception) {
            errorMsg = humanError(e)
            DebugLog.e(TAG, "playback failed '${audioItem.name}': $errorMsg", e)
        } finally {
            val err = errorMsg
            if (err == null) {
                DebugLog.i(TAG, "completed '${audioItem.name}'")
                listener.onPlaybackCompleted()
            } else {
                listener.onPlaybackFailed(err)
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun playOnce() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, audioItem.uri, null)
            DebugLog.d(TAG, "source ok: ${audioItem.uri} tracks=${extractor.trackCount}")

            val trackIndex = selectAudioTrack(extractor)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            DebugLog.d(TAG, "input format: $inputFormat")

            val codec = createDecoder(inputFormat)
            DebugLog.d(TAG, "decoder: ${codec.name}")
            try {
                codec.start()
                transcode(codec, extractor)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun transcode(codec: MediaCodec, extractor: MediaExtractor) {
        val info = MediaCodec.BufferInfo()
        var track: AudioTrack? = null
        var outputFormat: MediaFormat? = null
        var inputDone = false

        try {
            while (!cancelled) {
                val activeTrack = track
                if (paused) {
                    runCatching { activeTrack?.pause() }
                    Thread.sleep(50)
                    continue
                } else {
                    runCatching {
                        if (activeTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) activeTrack.play()
                    }
                }

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        DebugLog.i(TAG, "decoded format: ${describe(outputFormat)}")
                        if (track == null) track = createTrack(outputFormat)
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            if (track == null) {
                                outputFormat = runCatching { codec.outputFormat }.getOrNull()
                                    ?: throw IllegalStateException("decoder never reported an output format")
                                track = createTrack(outputFormat)
                            }
                            writePcm(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            DebugLog.d(TAG, "decoder EOS")
                            break
                        }
                    }

                    else -> throw IllegalStateException("dequeueOutputBuffer returned $outIndex")
                }
            }
        } finally {
            runCatching {
                if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            }
            runCatching { track?.release() }
            currentTrack = null
        }
    }

    private fun writePcm(track: AudioTrack, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val written = if (track.channelCount == 1 && outputChannels == 2) {
            val mono = downmixToMono(buffer, info.size)
            track.write(mono, 0, mono.size, AudioTrack.WRITE_BLOCKING)
        } else {
            track.write(buffer, info.size, AudioTrack.WRITE_BLOCKING)
        }

        if (written < 0) {
            throw IllegalStateException("AudioTrack.write error $written (${writeErrorName(written)})")
        }
    }

    private var outputChannels = 1

    private fun createTrack(format: MediaFormat): AudioTrack {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        outputChannels = channels

        // The telephony uplink is mono. Always feed mono to avoid HAL channel
        // mismatches; stereo sources are downmixed in writePcm().
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("getMinBufferSize failed ($minBuffer) for ${sampleRate}Hz")
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (useMediaUsage || preferredDevice == null) AudioAttributes.USAGE_MEDIA
                else AudioAttributes.USAGE_VOICE_COMMUNICATION
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            throw IllegalStateException("AudioTrack not initialized (state=${track.state})")
        }

        if (preferredDevice != null) {
            val ok = track.setPreferredDevice(preferredDevice)
            DebugLog.i(TAG, "setPreferredDevice(${preferredDevice.productName}) -> $ok")
            if (!ok) {
                DebugLog.w(TAG, "platform refused the telephony output device")
            }
        }

        val volume = Preferences(context).volume.coerceIn(0f, 1f)
        track.setVolume(volume)
        track.play()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            throw IllegalStateException("AudioTrack did not start (playState=${track.playState})")
        }
        DebugLog.i(TAG, "AudioTrack started: ${sampleRate}Hz mono, volume=$volume")
        currentTrack = track
        return track
    }

    private fun downmixToMono(buffer: ByteBuffer, size: Int): ByteArray {
        val samples = size / 2
        val out = ByteArray((samples / 2) * 2)
        val src = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        var o = 0
        while (i + 1 < samples) {
            val l = src.get(i).toInt()
            val r = src.get(i + 1).toInt()
            dst.put(o, ((l + r) / 2).toShort())
            i += 2
            o++
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                return i
            }
        }
        throw IllegalStateException("no audio track in ${audioItem.uri}")
    }

    private fun createDecoder(format: MediaFormat): MediaCodec {
        val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            ?: throw IllegalStateException("no decoder for ${format.getString(MediaFormat.KEY_MIME)}")
        val codec = MediaCodec.createByCodecName(decoderName)
        runCatching { codec.configure(format, null, null, 0) }
            .onFailure {
                codec.release()
                throw IllegalStateException("decoder '$decoderName' rejected the file", it)
            }
        return codec
    }

    private fun describe(format: MediaFormat?): String {
        if (format == null) return "null"
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
        val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
        return "${format.getString(MediaFormat.KEY_MIME)} ${rate}Hz ${ch}ch"
    }

    private fun humanError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("no audio track") -> "File has no audio track"
            msg.contains("no decoder") -> "No decoder for this format"
            msg.contains("getMinBufferSize") -> "Unsupported sample rate"
            msg.contains("AudioTrack not initialized") -> "Audio output rejected the format"
            msg.contains("did not start") -> "Audio output refused to start"
            else -> msg
        }
    }

    private fun writeErrorName(code: Int): String = when (code) {
        AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        else -> "ERROR_$code"
    }
}
