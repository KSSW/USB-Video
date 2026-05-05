package com.meta.usbvideo.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes logs to app's external files directory:
 *   /storage/emulated/0/Android/data/com.meta.usbvideo/files/usbvideo_log.txt
 * Pull via: adb pull /storage/emulated/0/Android/data/com.meta.usbvideo/files/usbvideo_log.txt
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_FILE_NAME = "usbvideo_log.txt"
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var logFilePath: String = ""
        private set

    fun init(context: Context) {
        try {
            // Use app's external files dir (no permission needed on Android 11+)
            val dir = context.getExternalFilesDir(null)
                ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, LOG_FILE_NAME)
            writer = PrintWriter(FileWriter(file, true), true)
            logFilePath = file.absolutePath
            log("FileLogger", "========== App started ==========")
            log("FileLogger", "Log file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init file logger: ${e.message}", e)
        }
    }

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp  I/$tag: $message"
        Log.i(tag, message)
        try {
            writer?.println(line)
        } catch (_: Exception) {}
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp  E/$tag: $message"
        Log.e(tag, message, throwable)
        try {
            writer?.println(line)
            throwable?.let { writer?.println("$timestamp  E/$tag: ${it.stackTraceToString()}") }
        } catch (_: Exception) {}
    }

    fun close() {
        try {
            log("FileLogger", "========== App stopped ==========")
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }
}
