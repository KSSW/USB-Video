package com.meta.usbvideo.usb

import android.content.Context
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper

private const val TAG = "UvcManager"
private const val PREFS_NAME = "usbvideo_prefs"
private const val KEY_FORMAT = "saved_video_format"

/**
 * Manages UVC (USB Video Class) device lifecycle: connect, start/stop preview, format negotiation.
 * Wraps [UsbMonitor], [VideoStreamingConnection] and the native JNI layer.
 */
object UvcManager {

    private var surface: Surface? = null
    private var currentFormat: VideoFormat? = null
    private var isStreaming = false
    private var appContext: Context? = null

    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun initContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            restoreFormat()
        }
    }

    private fun restoreFormat() {
        val p = prefs ?: return
        val saved = p.getString(KEY_FORMAT, null) ?: return
        val parts = saved.split(" ")
        if (parts.size >= 4) {
            var fourcc = parts[0]
            // Handle corrupted/legacy GBR24 saved format (GUID bytes parsed as chars)
            val gbr24Corrupted = setOf("}ë6ä", "}\u00EB6\u00E4")
            if (fourcc in gbr24Corrupted) {
                fourcc = "GBR24"
            }
            val w = parts[1].toIntOrNull()
            val h = parts[2].toIntOrNull()
            val fps = parts[3].toIntOrNull()
            if (w != null && h != null && fps != null) {
                currentFormat = VideoFormat(fourcc, w, h, fps)
                Log.i(TAG, "Restored format from prefs: $currentFormat")
            }
        }
    }

    private fun persistFormat(format: VideoFormat) {
        prefs?.edit()?.putString(KEY_FORMAT, "${format.fourccFormat} ${format.width} ${format.height} ${format.fps}")?.apply()
        Log.i(TAG, "Persisted format to prefs: $format")
    }

    val videoFormats: List<VideoFormat>
        get() {
            val state = UsbMonitor.usbDeviceState
            return when (state) {
                is UsbDeviceState.Connected -> state.videoStreamingConnection.videoFormats
                is UsbDeviceState.Streaming -> state.videoStreamingConnection.videoFormats
                is UsbDeviceState.StreamingStopped -> state.videoStreamingConnection.videoFormats
                else -> emptyList()
            }
        }

    val currentVideoFormat: VideoFormat?
        get() = currentFormat

    val isDeviceConnected: Boolean
        get() {
            val state = UsbMonitor.usbDeviceState
            return state is UsbDeviceState.Connected ||
                    state is UsbDeviceState.Streaming ||
                    state is UsbDeviceState.StreamingStopped
        }

    val isVideoStreaming: Boolean
        get() = isStreaming

    fun setSurface(surfaceTexture: SurfaceTexture) {
        surface?.release()
        surface = Surface(surfaceTexture)
    }

    fun releaseSurface() {
        surface?.release()
        surface = null
    }

    fun getSurface(): Surface? = surface

    fun selectVideoFormat(format: VideoFormat) {
        currentFormat = format
        persistFormat(format)
        Log.i(TAG, "Video format selected: $format")
    }

    fun findBestFormat(width: Int = 1920, height: Int = 1080): VideoFormat? {
        val state = UsbMonitor.usbDeviceState
        return when (state) {
            is UsbDeviceState.Connected -> state.videoStreamingConnection.findBestVideoFormat(width, height)
            is UsbDeviceState.Streaming -> state.videoStreamingConnection.findBestVideoFormat(width, height)
            else -> null
        }
    }

    suspend fun startStreaming(videoFormat: VideoFormat?, targetSurface: Surface): Pair<Boolean, String> {
        val state = UsbMonitor.usbDeviceState
        if (state !is UsbDeviceState.Connected && state !is UsbDeviceState.StreamingStopped) {
            return false to "Device not in connectable state: $state"
        }

        val format = videoFormat ?: findBestFormat() ?: return false to "No supported video format"
        currentFormat = format

        val conn = when (state) {
            is UsbDeviceState.Connected -> state.videoStreamingConnection
            is UsbDeviceState.StreamingStopped -> state.videoStreamingConnection
            else -> return false to "Unexpected state"
        }

        val result = EventLooper.call {
            UsbVideoNativeLibrary.connectUsbVideoStreaming(conn, targetSurface, format)
                .also { UsbVideoNativeLibrary.startUsbVideoStreamingNative() }
        }

        isStreaming = result.first
        Log.i(TAG, "startStreaming result=$result, format=$format")
        return result
    }

    suspend fun stopStreaming() {
        EventLooper.call {
            UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
        }
        isStreaming = false
        Log.i(TAG, "stopStreaming completed")
    }

    fun getDeviceProductName(): String? {
        return when (val state = UsbMonitor.usbDeviceState) {
            is UsbDeviceState.Connected -> state.usbDevice.productName
            is UsbDeviceState.Streaming -> state.usbDevice.productName
            is UsbDeviceState.StreamingStopped -> state.usbDevice.productName
            else -> null
        }
    }
}
