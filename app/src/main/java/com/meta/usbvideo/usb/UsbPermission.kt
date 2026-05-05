package com.meta.usbvideo.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private const val TAG = "UsbPermission"
private const val ACTION_USB_PERMISSION = "com.meta.usbvideo.USB_PERMISSION"

/**
 * Handles USB device permission requests and broadcast receivers for attach/detach events.
 */
object UsbPermission {

    private var receiver: BroadcastReceiver? = null

    fun registerUsbReceivers(
        activity: ComponentActivity,
        onAttach: (UsbDevice) -> Unit,
        onDetach: (UsbDevice) -> Unit,
        onPermissionResult: (UsbDevice, Boolean) -> Unit
    ) {
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = IntentCompat.getParcelableExtra(
                            intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                        )
                        if (device != null) {
                            Log.i(TAG, "USB device attached: ${device.productName}")
                            onAttach(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = IntentCompat.getParcelableExtra(
                            intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                        )
                        if (device != null) {
                            Log.i(TAG, "USB device detached: ${device.productName}")
                            onDetach(device)
                        }
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = UsbMonitor.findUvcDevice()
                        if (device != null) {
                            val granted = UsbMonitor.getUsbManager()?.hasPermission(device) == true
                            Log.i(TAG, "USB permission result for ${device.productName}: granted=$granted")
                            onPermissionResult(device, granted)
                        }
                    }
                }
            }
        }

        receiver = usbReceiver
        activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        activity.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)

        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                activity.unregisterReceiver(usbReceiver)
                receiver = null
            }
        })
    }

    fun requestPermission(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Device already has permission")
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "USB permission requested for ${device.productName}")
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.hasPermission(device)
    }
}
