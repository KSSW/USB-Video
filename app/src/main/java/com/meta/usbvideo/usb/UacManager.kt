package com.meta.usbvideo.usb

import android.content.Context
import android.util.Log
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper

private const val TAG = "UacManager"

/**
 * Manages UAC (USB Audio Class) device lifecycle: connect, start/stop audio capture.
 * Wraps [AudioStreamingConnection] and the native JNI layer.
 */
object UacManager {

    private var isStreaming = false

    val isAudioStreaming: Boolean
        get() = isStreaming

    suspend fun startAudioStreaming(context: Context): Pair<Boolean, String> {
        val state = UsbMonitor.usbDeviceState
        val conn = when (state) {
            is UsbDeviceState.Connected -> state.audioStreamingConnection
            is UsbDeviceState.StreamingStopped -> state.audioStreamingConnection
            else -> return false to "Device not in connectable state"
        }

        val result = EventLooper.call {
            UsbVideoNativeLibrary.connectUsbAudioStreaming(context, conn)
                .also { UsbVideoNativeLibrary.startUsbAudioStreamingNative() }
        }

        isStreaming = result.first
        Log.i(TAG, "startAudioStreaming result=$result")
        return result
    }

    suspend fun stopAudioStreaming() {
        EventLooper.call {
            UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
        }
        isStreaming = false
        Log.i(TAG, "stopAudioStreaming completed")
    }
}
