package com.meta.usbvideo.ui

import android.app.Dialog
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meta.usbvideo.R
import com.meta.usbvideo.usb.UsbMonitor

class DeviceListDialogFragment : DialogFragment() {

    var onDeviceSelected: ((UsbDevice) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val devices = UsbMonitor.findAllUvcDevices()

        val builder = AlertDialog.Builder(requireActivity())
        builder.setTitle(R.string.device_list_title)

        if (devices.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = getString(R.string.device_list_empty)
                textSize = 16f
                setPadding(48, 48, 48, 48)
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
            }
            builder.setView(tv)
        } else {
            val recyclerView = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = DeviceAdapter(devices) { device ->
                    onDeviceSelected?.invoke(device)
                    dismiss()
                }
                setPadding(0, 16, 0, 16)
            }
            builder.setView(recyclerView)
        }

        builder.setNegativeButton(android.R.string.cancel) { dialog, _ ->
            dialog.dismiss()
        }

        return builder.create()
    }

    private class DeviceAdapter(
        private val devices: List<UsbDevice>,
        private val onClick: (UsbDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvProductName: TextView = view.findViewById(R.id.tvProductName)
            val tvDeviceInfo: TextView = view.findViewById(R.id.tvDeviceInfo)
            val ivDeviceIcon: ImageView = view.findViewById(R.id.ivDeviceIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvProductName.text = device.productName ?: "USB Capture Device"
            holder.tvDeviceInfo.text = String.format(
                "VID:%04X  PID:%04X  %s",
                device.vendorId,
                device.productId,
                device.deviceName
            )
            holder.itemView.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size
    }

    companion object {
        const val TAG = "DeviceListDialog"
    }
}
