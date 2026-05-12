package com.meta.usbvideo.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes logs to app's external files directory:
 *   /storage/emulated/0/Android/data/com.meta.usbvideo/files/usbvideo_log.txt
 * Pull via: adb pull /storage/emulated/0/Android/data/com.meta.usbvideo/files/usbvideo_log.txt
 *
 * File logging can be enabled/disabled at runtime via [enabled].
 * Default: OFF (disabled). Toggle in Settings > Log Settings.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_FILE_NAME = "usbvideo_log.txt"
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var logFilePath: String = ""
        private set

    /** Whether file logging is enabled. Default OFF. */
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                openWriter()
                log(TAG, "File logging ENABLED")
            } else {
                log(TAG, "File logging DISABLED")
                closeWriter()
            }
        }

    /** Total log lines written since app start */
    val totalFramesWritten: AtomicLong = AtomicLong(0)

    /** Timestamp of first log write (for frame rate calculation) */
    private var firstWriteTimeMs: Long = 0L

    /** Expected frame rate from capture device (set externally) */
    var expectedFps: Int = 60

    private var logDir: File? = null

    fun init(context: Context) {
        logDir = context.getExternalFilesDir(null) ?: context.filesDir
        logDir?.mkdirs()
        val file = File(logDir!!, LOG_FILE_NAME)
        logFilePath = file.absolutePath
        // Don't open writer yet - wait for enabled = true
        // Always output to logcat
        Log.i(TAG, "FileLogger initialized, file=$logFilePath, enabled=$enabled")
    }

    private fun openWriter() {
        try {
            val dir = logDir ?: return
            dir.mkdirs()
            val file = File(dir, LOG_FILE_NAME)
            writer = PrintWriter(FileWriter(file, true), true)
            logFilePath = file.absolutePath
            totalFramesWritten.set(0)
            firstWriteTimeMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file logger: ${e.message}", e)
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }

    @JvmStatic
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        if (!enabled) return
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp  I/$tag: $message"
        try {
            writer?.println(line)
            totalFramesWritten.incrementAndGet()
        } catch (_: Exception) {}
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (!enabled) return
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp  E/$tag: $message"
        try {
            writer?.println(line)
            throwable?.let { writer?.println("$timestamp  E/$tag: ${it.stackTraceToString()}") }
            totalFramesWritten.incrementAndGet()
        } catch (_: Exception) {}
    }

    fun close() {
        if (enabled) {
            log(TAG, "========== App stopped ==========")
        }
        closeWriter()
    }

    // --- Status query methods for UI ---

    /** Get current log file size in bytes */
    fun getLogFileSizeBytes(): Long {
        return try {
            val file = File(logFilePath)
            if (file.exists()) file.length() else 0L
        } catch (_: Exception) { 0L }
    }

    /** Get free storage space in bytes */
    fun getFreeStorageBytes(): Long {
        return try {
            val stat = StatFs(logDir?.absolutePath ?: Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) { 0L }
    }

    /** Get formatted log file size (e.g. "12 MB") */
    fun getLogFileSizeFormatted(): String {
        val bytes = getLogFileSizeBytes()
        return formatBytes(bytes)
    }

    /** Get formatted free storage (e.g. "256 GB") */
    fun getFreeStorageFormatted(): String {
        val bytes = getFreeStorageBytes()
        return formatBytes(bytes)
    }

    /** 
     * Get frame write status.
     * Returns "Continuous OK" if write rate meets expected FPS,
     * otherwise returns actual write rate info.
     */
    fun getFrameStatus(): String {
        val frames = totalFramesWritten.get()
        if (frames == 0L || firstWriteTimeMs == 0L) {
            return "$frames Frames | --"
        }
        val elapsedMs = System.currentTimeMillis() - firstWriteTimeMs
        if (elapsedMs < 1000) {
            return "$frames Frames | Measuring..."
        }
        val actualFps = (frames * 1000.0 / elapsedMs)
        return if (actualFps >= expectedFps * 0.95) {
            "$frames Frames | Continuous OK"
        } else {
            "$frames Frames | ${String.format("%.1f", actualFps)} fps (expected $expectedFps)"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
