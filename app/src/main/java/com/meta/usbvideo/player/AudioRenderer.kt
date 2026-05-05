package com.meta.usbvideo.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AudioRenderer"
private const val MAX_CHANNELS = 8

/**
 * Manages audio playback from UAC device to the phone speaker.
 * Supports 2.0 / 5.1 / 7.1 multi-channel output.
 * Provides per-channel audio level metering for the AudioLevelMeterView.
 */
class AudioRenderer {

    private var isMuted = false
    private var volume = 1.0f
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var activeChannelCount = 2

    // Per-channel audio levels (up to 8)
    @Volatile private var levels = FloatArray(MAX_CHANNELS)

    fun setMuted(muted: Boolean) {
        isMuted = muted
        audioTrack?.let {
            if (muted) {
                it.setVolume(0f)
            } else {
                it.setVolume(volume)
            }
        }
        Log.i(TAG, "Audio muted: $muted")
    }

    fun isMuted(): Boolean = isMuted

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        if (!isMuted) {
            audioTrack?.setVolume(volume)
        }
        Log.i(TAG, "Audio volume: $volume")
    }

    fun getVolume(): Float = volume

    /**
     * Start playback through the phone speaker.
     * Supports channelCount: 1, 2, 6 (5.1), 8 (7.1).
     */
    fun startSpeakerPlayback(
        sampleRate: Int = 48000,
        channelCount: Int = 2,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT
    ) {
        if (isPlaying) return

        activeChannelCount = channelCount.coerceIn(1, MAX_CHANNELS)

        val channelMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding) * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true
        Log.i(TAG, "Speaker playback started: ${sampleRate}Hz, ${channelCount}ch, " +
                "mask=0x${channelMask.toString(16)}, bufferSize=$bufferSize")
    }

    /**
     * Write PCM audio data to the speaker and compute per-channel audio levels.
     * Called from the native audio streaming callback.
     */
    fun writePcmData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isPlaying) return

        // Compute per-channel audio levels from PCM 16-bit samples
        computeLevels(data, offset, length)

        // Write to speaker
        if (!isMuted) {
            audioTrack?.write(data, offset, length)
        }
    }

    /**
     * Get current audio levels for the level meter.
     * Returns floats in range 0.0–1.0, array size = activeChannelCount.
     */
    fun getAudioLevels(): FloatArray {
        return levels.copyOf(activeChannelCount)
    }

    fun getChannelCount(): Int = activeChannelCount

    fun stopSpeakerPlayback() {
        if (!isPlaying) return
        isPlaying = false
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        levels = FloatArray(MAX_CHANNELS)
        Log.i(TAG, "Speaker playback stopped")
    }

    fun release() {
        stopSpeakerPlayback()
        Log.i(TAG, "AudioRenderer released")
    }

    /**
     * Compute RMS audio levels from PCM 16-bit interleaved data.
     * Supports up to 8 channels (2.0 / 5.1 / 7.1).
     */
    private fun computeLevels(data: ByteArray, offset: Int, length: Int) {
        val bytesPerSample = 2 // 16-bit
        val frameSize = bytesPerSample * activeChannelCount
        if (length < frameSize) return

        val sums = DoubleArray(activeChannelCount)
        val counts = IntArray(activeChannelCount)

        var i = offset
        val end = offset + length
        while (i + frameSize <= end) {
            for (ch in 0 until activeChannelCount) {
                val pos = i + ch * bytesPerSample
                val raw = (data[pos].toInt() and 0xFF) or (data[pos + 1].toInt() shl 8)
                val signed = if (raw > 32767) raw - 65536 else raw
                sums[ch] += signed.toDouble() * signed.toDouble()
                counts[ch]++
            }
            i += frameSize
        }

        for (ch in 0 until activeChannelCount) {
            if (counts[ch] > 0) {
                val rms = sqrt(sums[ch] / counts[ch])
                levels[ch] = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
            }
        }
    }
}
