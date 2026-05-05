package com.meta.usbvideo.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

private const val TAG = "H264Encoder"

/**
 * Hardware H.264 encoder using Android MediaCodec.
 * Accepts raw video frames via an input Surface and outputs encoded H.264 NAL units.
 *
 * Used for local recording (with RawMuxer) and RTMP/SRT streaming.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 8_000_000
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false

    interface EncoderCallback {
        fun onEncodedData(data: ByteArray, info: MediaCodec.BufferInfo)
        fun onFormatChanged(format: MediaFormat)
    }

    private var callback: EncoderCallback? = null

    fun setCallback(cb: EncoderCallback) {
        callback = cb
    }

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }

        Log.i(TAG, "H264Encoder prepared: ${width}x${height} @${fps}fps, bitRate=$bitRate")
    }

    fun start() {
        mediaCodec?.start()
        isRunning = true
        Log.i(TAG, "H264Encoder started")
    }

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling end of stream", e)
        }
        Log.i(TAG, "H264Encoder stopped")
    }

    fun release() {
        stop()
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        inputSurface = null
    }

    fun getInputSurface(): Surface? = inputSurface

    fun isRunning(): Boolean = isRunning
}
