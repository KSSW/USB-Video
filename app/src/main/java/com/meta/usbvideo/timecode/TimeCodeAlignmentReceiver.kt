package com.meta.usbvideo.timecode

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

private const val TAG = "TimeCodeAlignment"

/**
 * Small LAN trigger receiver used to align this app's recorder with OBS.
 *
 * Protocol, UTF-8 UDP datagram:
 *   USBVIDEO_TIMECODE_ALIGN PING  <epoch_ms>
 *   USBVIDEO_TIMECODE_ALIGN START <epoch_ms>
 *   USBVIDEO_TIMECODE_ALIGN STOP  <epoch_ms>
 *   USBVIDEO_TIMECODE_ALIGN STATUS <epoch_ms> running=1 recording=0 camera=1 fps=1
 *
 * OBS sends START/STOP as LAN broadcast/direct UDP; the phone sends STATUS
 * heartbeat broadcasts every second so OBS can show the app state and learn
 * the phone IP for direct triggers.
 */
class TimeCodeAlignmentReceiver(
    private val onCommand: (Command, InetAddress, Long) -> Unit,
    private val statusProvider: () -> String = { "running=1 recording=0 camera=0 fps=0" },
    private val directStatusHostProvider: () -> String? = { null }
) {
    enum class Command { START, STOP, HOVER_START, HOVER_END }

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::receiveLoop, "timecode_alignment_udp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread = null
    }

    private fun receiveLoop() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 250
                bind(InetSocketAddress(PORT))
            }
            socket = s
            Log.i(TAG, "Listening for OBS TimeCode Alignment UDP on port $PORT")

            val buffer = ByteArray(1024)
            var lastStatusSentMs = 0L
            while (running) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatusSentMs >= STATUS_INTERVAL_MS) {
                    sendStatusHeartbeat(s, nowMs)
                    lastStatusSentMs = nowMs
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val message = String(
                    packet.data,
                    packet.offset,
                    packet.length,
                    StandardCharsets.UTF_8
                ).trim()

                val parts = message.split(Regex("\\s+"))
                if (parts.size < 2 || parts[0] != MAGIC) {
                    continue
                }

                val verb = parts[1].uppercase()
                val sentAtMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                if (verb == "PING") {
                    Log.i(TAG, "Received PING from ${packet.address.hostAddress}:${packet.port}")
                    sendStatusTo(s, packet.address, packet.port, System.currentTimeMillis())
                    continue
                }

                val command = when (verb) {
                    "START" -> Command.START
                    "STOP" -> Command.STOP
                    "HOVER_START" -> Command.HOVER_START
                    "HOVER_END" -> Command.HOVER_END
                    else -> null
                } ?: continue

                Log.i(TAG, "Received $command from ${packet.address.hostAddress}: $message")
                onCommand(command, packet.address, sentAtMs)
                sendStatusTo(s, packet.address, packet.port, System.currentTimeMillis())
            }
        } catch (e: SocketException) {
            if (running) {
                Log.e(TAG, "UDP socket error", e)
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "UDP receive loop failed", e)
            }
        } finally {
            socket?.close()
            socket = null
            running = false
            Log.i(TAG, "Stopped OBS TimeCode Alignment UDP receiver")
        }
    }

    private fun sendStatusHeartbeat(socket: DatagramSocket, nowMs: Long) {
        val message = "$MAGIC STATUS $nowMs ${statusProvider()}"
        val data = message.toByteArray(StandardCharsets.UTF_8)

        val targets = mutableSetOf<InetAddress>()
        try {
            targets += InetAddress.getByName("255.255.255.255")
        } catch (_: Exception) {
        }

        val directHost = directStatusHostProvider()?.trim().orEmpty()
        if (directHost.isNotEmpty()) {
            try {
                targets += InetAddress.getByName(directHost)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid OBS direct host: $directHost", e)
            }
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (entry in nif.interfaceAddresses) {
                    val broadcast = entry.broadcast
                    if (broadcast != null) {
                        targets += broadcast
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate broadcast addresses", e)
        }

        for (target in targets) {
            sendStatusBytesTo(socket, data, target, PORT)
        }
    }

    private fun sendStatusTo(socket: DatagramSocket, target: InetAddress, port: Int, nowMs: Long) {
        val message = "$MAGIC STATUS $nowMs ${statusProvider()}"
        val data = message.toByteArray(StandardCharsets.UTF_8)
        sendStatusBytesTo(socket, data, target, port)
    }

    private fun sendStatusBytesTo(socket: DatagramSocket, data: ByteArray, target: InetAddress, port: Int) {
        try {
            socket.send(DatagramPacket(data, data.size, target, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send status to ${target.hostAddress}:$port", e)
        }
    }

    companion object {
        const val PORT = 39274
        const val MAGIC = "USBVIDEO_TIMECODE_ALIGN"
        private const val STATUS_INTERVAL_MS = 1000L

        fun getLocalIpv4Addresses(): List<String> {
            val result = mutableListOf<String>()
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue

                    val addresses = nif.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            result += address.hostAddress ?: continue
                        }
                    }
                }
                result.distinct()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enumerate local IPv4 addresses", e)
                emptyList()
            }
        }
    }
}
