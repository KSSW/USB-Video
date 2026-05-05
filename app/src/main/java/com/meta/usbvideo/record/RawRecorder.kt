package com.meta.usbvideo.record

import android.util.Log
import java.io.File

private const val TAG = "RawRecorder"

/**
 * FFmpeg-based raw bitstream recorder.
 * Supports source format passthrough (MJPEG/YUY2/GBR24/...) to AVI/MP4/MOV/MKV.
 */
class RawRecorder {

    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate()
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    fun start(
        path: String,
        width: Int,
        height: Int,
        fps: Int,
        srcFormat: VideoSourceFormat,
        containerFormat: ContainerFormat,
        audioSampleRate: Int = 0,
        audioChannels: Int = 0,
        audioBitsPerSample: Int = 0
    ): Boolean {
        if (nativeHandle == 0L) return false
        val ret = nativeStart(
            nativeHandle, path, width, height, fps,
            srcFormat.ordinal, containerFormat.ordinal,
            audioSampleRate, audioChannels, audioBitsPerSample
        )
        return ret == 0
    }

    fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    fun isRecording(): Boolean {
        return nativeHandle != 0L && nativeIsRecording(nativeHandle)
    }

    fun getLastError(): String {
        return if (nativeHandle != 0L) nativeGetLastError(nativeHandle) else "Not initialized"
    }

    fun writeFrame(data: ByteArray, pts: Long = -1): Boolean {
        if (nativeHandle == 0L) return false
        return nativeWriteFrame(nativeHandle, data, pts) == 0
    }

    fun setupAudioStream(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeSetupAudio(nativeHandle, sampleRate, channels, bitsPerSample) == 0
    }

    fun writeAudio(data: ByteArray, pts: Long = -1): Boolean {
        if (nativeHandle == 0L) return false
        return nativeWriteAudio(nativeHandle, data, pts) == 0
    }

    companion object {
        fun getVideoOutputDir(): File {
            val base = android.os.Environment.getExternalStorageDirectory()
            val dir = File(base, "USB Video/Video")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getAudioOutputDir(): File {
            val base = android.os.Environment.getExternalStorageDirectory()
            val dir = File(base, "USB Video/Audio")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getPicturesOutputDir(): File {
            val base = android.os.Environment.getExternalStorageDirectory()
            val dir = File(base, "USB Video/Pictures")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeStart(
            handle: Long, path: String, width: Int, height: Int, fps: Int,
            srcFormat: Int, containerFormat: Int,
            audioSampleRate: Int, audioChannels: Int, audioBitsPerSample: Int
        ): Int

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeIsRecording(handle: Long): Boolean

        @JvmStatic
        private external fun nativeGetLastError(handle: Long): String

        @JvmStatic
        private external fun nativeWriteFrame(handle: Long, data: ByteArray, pts: Long): Int

        @JvmStatic
        private external fun nativeSetupAudio(handle: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): Int

        @JvmStatic
        private external fun nativeWriteAudio(handle: Long, data: ByteArray, pts: Long): Int
    }
}

enum class VideoSourceFormat {
    MJPEG, YUYV, UYVY, NV12, NV21, BGR24, RGB24, UNKNOWN
}

enum class ContainerFormat {
    AVI, MP4, MOV, MKV, AUTO
}
