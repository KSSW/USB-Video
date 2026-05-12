package com.meta.usbvideo.record

import android.content.Context
import android.os.Environment
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
        private const val PREFS_NAME = "save_files_settings"
        private const val KEY_CUSTOM_OUTPUT_BASE_DIR = "custom_output_base_dir"

        @Volatile
        private var customOutputBaseDir: File? = null

        fun initOutputSettings(context: Context) {
            val savedPath = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_OUTPUT_BASE_DIR, null)
            customOutputBaseDir = savedPath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
            ensureDefaultOutputDirs()
        }

        fun setCustomOutputBaseDir(context: Context, path: String?) {
            val cleanPath = path?.trim()?.takeIf { it.isNotEmpty() }
            customOutputBaseDir = cleanPath?.let { File(it) }
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_OUTPUT_BASE_DIR, cleanPath)
                .apply()
            ensureDefaultOutputDirs()
            Log.i(TAG, "Custom output base dir set to: ${getOutputBaseDir().absolutePath}")
        }

        fun getOutputBaseDir(): File {
            return customOutputBaseDir ?: File(Environment.getExternalStorageDirectory(), "USB Video")
        }

        fun getOutputBasePath(): String {
            return getOutputBaseDir().absolutePath
        }

        private fun ensureDefaultOutputDirs() {
            ensureWritableDir(File(getOutputBaseDir(), "Video"))
            ensureWritableDir(File(getOutputBaseDir(), "Audio"))
            ensureWritableDir(File(getOutputBaseDir(), "Pictures"))
        }

        private fun ensureWritableDir(dir: File): File {
            if (!dir.exists()) {
                val ok = dir.mkdirs()
                Log.i(TAG, "mkdirs ${dir.absolutePath}: $ok")
            }
            // Best effort: on Android shared storage this is controlled by the
            // platform/All files access, but keep the directory as permissive as
            // the filesystem allows for native FFmpeg direct-path writes.
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
            return dir
        }

        fun getVideoOutputDir(): File {
            return ensureWritableDir(File(getOutputBaseDir(), "Video"))
        }

        fun getAudioOutputDir(): File {
            return ensureWritableDir(File(getOutputBaseDir(), "Audio"))
        }

        fun getPicturesOutputDir(): File {
            return ensureWritableDir(File(getOutputBaseDir(), "Pictures"))
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
