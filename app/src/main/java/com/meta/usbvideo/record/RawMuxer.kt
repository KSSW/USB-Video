package com.meta.usbvideo.record

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RawMuxer"

/**
 * Records video frames from the preview surface to MP4/MKV files.
 * Uses MediaCodec for H.264 encoding and MediaMuxer for container muxing.
 */
class RawMuxer {

    interface RecordCallback {
        fun onRecordStarted()
        fun onRecordStopped(file: File)
        fun onRecordError(message: String)
        fun onRecordTimeUpdate(timeText: String)
    }

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var isRecording = false
    private var recordStartTime = 0L
    private var videoTrackIndex = -1
    private var callback: RecordCallback? = null
    private var outputFile: File? = null

    val recording: Boolean
        get() = isRecording

    fun setCallback(callback: RecordCallback) {
        this.callback = callback
    }

    fun getVideoOutputDir(): File {
        val base = File(Environment.getExternalStorageDirectory(), "USB Video")
        val dir = File(base, "Video")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAudioOutputDir(): File {
        val base = File(Environment.getExternalStorageDirectory(), "USB Video")
        val dir = File(base, "Audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPicturesOutputDir(): File {
        val base = File(Environment.getExternalStorageDirectory(), "USB Video")
        val dir = File(base, "Pictures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun startRecording(width: Int, height: Int, fps: Int, bitRate: Int = 8_000_000) {
        if (isRecording) return

        try {
            val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(Date())
            outputFile = File(getVideoOutputDir(), "VID_$timestamp.mp4")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRecording = true
            recordStartTime = SystemClock.elapsedRealtime()
            callback?.onRecordStarted()

            // Start encoding thread
            startEncodingThread()

            Log.i(TAG, "Recording started: ${outputFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            callback?.onRecordError("Failed to start recording: ${e.message}")
            stopRecording()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            mediaCodec?.signalEndOfInputStream()
            // Encoding thread will handle cleanup
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            releaseCodec()
        }
    }

    fun getInputSurface(): Surface? = inputSurface

    fun getRecordDuration(): Long {
        return if (isRecording && recordStartTime > 0) {
            (SystemClock.elapsedRealtime() - recordStartTime) / 1000
        } else 0
    }

    fun formatTime(timeSec: Long): String {
        val df = DecimalFormat("00")
        val hh = df.format(timeSec / 3600)
        val mm = df.format(timeSec % 3600 / 60)
        val ss = df.format(timeSec % 60)
        return "$hh:$mm:$ss"
    }

    fun release() {
        stopRecording()
        releaseCodec()
    }

    private fun startEncodingThread() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var muxerStarted = false

            while (isRecording || true) {
                val codec = mediaCodec ?: break
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                        mediaMuxer?.start()
                        muxerStarted = true
                        Log.i(TAG, "Muxer started, track=$videoTrackIndex")
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && muxerStarted && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "End of stream reached")
                            break
                        }
                    }
                }

                if (!isRecording && outputIndex < 0) break
            }

            releaseCodec()
            outputFile?.let { file ->
                Log.i(TAG, "Recording saved: ${file.absolutePath}")
                callback?.onRecordStopped(file)
            }
        }.apply {
            name = "RawMuxer-Encoder"
            start()
        }
    }

    private fun releaseCodec() {
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        inputSurface = null

        try {
            mediaMuxer?.stop()
        } catch (_: Exception) {}
        try {
            mediaMuxer?.release()
        } catch (_: Exception) {}
        mediaMuxer = null
        videoTrackIndex = -1
    }

    companion object {
        fun captureScreenshot(bitmap: Bitmap): File? {
            return try {
                val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(Date())
                val dir = RawRecorder.getPicturesOutputDir()
                val file = File(dir, "IMG_$timestamp.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                Log.i(TAG, "Screenshot saved: ${file.absolutePath}")
                file
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
                null
            }
        }
    }
}
