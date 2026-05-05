package com.meta.usbvideo.encoder

import android.media.AudioFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "LpcmEncoder"

/**
 * LPCM (Linear Pulse Code Modulation) audio encoder / passthrough.
 * Directly writes raw PCM audio data from the UAC device to a WAV file
 * without any encoding or compression.
 */
class LpcmEncoder(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val bitsPerSample: Int = 16
) {
    private var outputStream: OutputStream? = null
    private var outputFile: File? = null
    private var isRunning = false
    private var totalBytesWritten = 0L

    interface LpcmCallback {
        fun onDataWritten(bytesWritten: Long)
        fun onError(message: String)
    }

    private var callback: LpcmCallback? = null

    fun setCallback(cb: LpcmCallback) {
        callback = cb
    }

    val audioFormat: Int
        get() = when (bitsPerSample) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

    fun prepare(file: File) {
        outputFile = file
        totalBytesWritten = 0L
        Log.i(TAG, "LpcmEncoder prepared: sampleRate=$sampleRate, channels=$channelCount, bits=$bitsPerSample, file=${file.absolutePath}")
    }

    fun start() {
        val file = outputFile ?: run {
            callback?.onError("Output file not set")
            return
        }
        try {
            outputStream = FileOutputStream(file)
            // Write WAV header placeholder (will be updated on stop)
            writeWavHeader(outputStream!!, 0)
            isRunning = true
            Log.i(TAG, "LpcmEncoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LpcmEncoder", e)
            callback?.onError("Failed to start: ${e.message}")
        }
    }

    fun writePcmData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isRunning) return
        try {
            outputStream?.write(data, offset, length)
            totalBytesWritten += length
            callback?.onDataWritten(totalBytesWritten)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
            callback?.onError("Write error: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            outputStream?.close()
            // Update WAV header with actual data size
            outputFile?.let { updateWavHeader(it, totalBytesWritten) }
            Log.i(TAG, "LpcmEncoder stopped, total bytes: $totalBytesWritten")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping LpcmEncoder", e)
        }
        outputStream = null
    }

    fun release() {
        stop()
        outputFile = null
    }

    fun isRunning(): Boolean = isRunning

    fun getTotalBytesWritten(): Long = totalBytesWritten

    private fun writeWavHeader(out: OutputStream, dataSize: Long) {
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val totalSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(totalSize.toInt())
        header.put("WAVE".toByteArray())
        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // chunk size
        header.putShort(1) // PCM format
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        // data chunk
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())

        out.write(header.array())
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        try {
            val raf = java.io.RandomAccessFile(file, "rw")
            val totalSize = 36 + dataSize
            val byteRate = sampleRate * channelCount * bitsPerSample / 8
            val blockAlign = channelCount * bitsPerSample / 8

            // Update RIFF chunk size
            raf.seek(4)
            raf.write(intToLittleEndian(totalSize.toInt()))

            // Update data chunk size
            raf.seek(40)
            raf.write(intToLittleEndian(dataSize.toInt()))

            raf.close()
            Log.i(TAG, "WAV header updated: dataSize=$dataSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header", e)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
