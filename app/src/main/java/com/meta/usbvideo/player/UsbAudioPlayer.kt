package com.meta.usbvideo.player

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.meta.usbvideo.usb.AudioStreamingConnection
import com.meta.usbvideo.util.FileLogger
import java.nio.ByteBuffer
import kotlin.concurrent.thread

private const val TAG = "UsbAudioPlayer"

/**
 * Java-side USB audio player that reads isochronous audio data from USB
 * and plays it through Android AudioTrack.
 * 
 * This bypasses the native audio layer which doesn't handle 24-bit PCM properly.
 */
class UsbAudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var readThread: Thread? = null
    private var usbRequest: UsbRequest? = null
    @Volatile private var isRunning = false

    /**
     * Start audio playback from the USB audio device.
     * Reads raw PCM from USB isochronous endpoint, converts 24-bit to 16-bit, plays via AudioTrack.
     */
    fun start(
        usbDevice: UsbDevice,
        connection: UsbDeviceConnection,
        audioConn: AudioStreamingConnection,
    ) {
        if (isRunning) return

        val format = audioConn.formatTypeDescriptor
        val ifaceDesc = audioConn.interfaceDescriptor
        val epDesc = audioConn.endpointDescriptor

        val channelCount = format.bNrChannels
        val sampleRate = format.tSamFreq.firstOrNull() ?: 48000
        val subFrameSize = format.bSubFrameSize
        val interfaceNumber = ifaceDesc.bInterfaceNumber
        val alternateSetting = ifaceDesc.bAlternateSetting
        val endpointAddress = epDesc.bEndpointAddress
        val maxPacketSize = epDesc.wMaxPacketSize

        FileLogger.log(TAG, "Starting USB audio: ch=$channelCount, rate=$sampleRate, " +
                "subFrame=$subFrameSize, iface=$interfaceNumber, alt=$alternateSetting, " +
                "ep=0x${endpointAddress.toString(16)}, maxPkt=$maxPacketSize")

        // Find the actual UsbInterface and UsbEndpoint from the UsbDevice
        var usbInterface: UsbInterface? = null
        var usbEndpoint: UsbEndpoint? = null

        for (i in 0 until usbDevice.interfaceCount) {
            val iface = usbDevice.getInterface(i)
            if (iface.id == interfaceNumber && iface.alternateSetting == alternateSetting) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.address == endpointAddress) {
                        usbInterface = iface
                        usbEndpoint = ep
                        break
                    }
                }
                if (usbEndpoint != null) break
            }
        }

        if (usbInterface == null || usbEndpoint == null) {
            FileLogger.error(TAG, "Could not find USB audio interface/endpoint")
            return
        }

        // Claim the interface
        if (!connection.claimInterface(usbInterface, true)) {
            FileLogger.error(TAG, "Failed to claim USB audio interface")
            return
        }

        // Set alternate setting
        connection.setInterface(usbInterface)

        FileLogger.log(TAG, "USB audio interface claimed, endpoint type=${usbEndpoint.type}")

        // Create AudioTrack for 16-bit output, supporting multi-channel
        val channelMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )

        FileLogger.log(TAG, "Creating AudioTrack: ch=$channelCount, mask=0x${channelMask.toString(16)}, " +
                "rate=$sampleRate, minBuf=$bufferSize")

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        FileLogger.log(TAG, "AudioTrack created: rate=$sampleRate, bufSize=$bufferSize")

        // Setup UsbRequest for isochronous transfer
        val request = UsbRequest()
        if (!request.initialize(connection, usbEndpoint)) {
            FileLogger.error(TAG, "UsbRequest initialization failed")
            return
        }
        usbRequest = request
        FileLogger.log(TAG, "UsbRequest initialized for isochronous endpoint")

        isRunning = true
        val pktSize = maxPacketSize.coerceAtLeast(192)

        readThread = thread(name = "UsbAudioReader") {
            FileLogger.log(TAG, "Audio read thread started")
            try {
                while (isRunning) {
                    val buf = ByteBuffer.allocate(pktSize)
                    request.queue(buf, pktSize)
                    val resp = connection.requestWait(1000)
                    if (resp != null && resp == request) {
                        buf.rewind()
                        val bytesRead = buf.remaining()
                        if (bytesRead > 0) {
                            val data = ByteArray(bytesRead)
                            buf.get(data)
                            val samples = bytesRead / (subFrameSize * channelCount)
                            if (samples > 0) {
                                val outBuffer = ShortArray(samples * channelCount)
                                for (i in 0 until samples * channelCount) {
                                    val offset = i * subFrameSize
                                    if (offset + subFrameSize > bytesRead) break
                                    outBuffer[i] = when (subFrameSize) {
                                        2 -> ((data[offset + 1].toInt() shl 8) or
                                                (data[offset].toInt() and 0xFF)).toShort()
                                        3, 4 -> ((data[offset + 2].toInt() shl 8) or
                                                (data[offset + 1].toInt() and 0xFF)).toShort()
                                        else -> 0
                                    }
                                }
                                track.write(outBuffer, 0, samples * channelCount)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Audio read error", e)
            }
            FileLogger.log(TAG, "Audio read thread ended")
        }
    }

    fun stop() {
        isRunning = false
        readThread?.join(1000)
        readThread = null
        usbRequest?.close()
        usbRequest = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        FileLogger.log(TAG, "Audio stopped")
    }
}
