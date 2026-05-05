package com.meta.usbvideo.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.meta.usbvideo.util.FileLogger

private const val TAG = "XbmcAudioManager"

/**
 * Audio output manager inspired by Kodi/XBMC's AESinkAUDIOTRACK.
 *
 * Key features:
 * - Detects if device has built-in speaker (TV boxes often don't)
 * - Supports AudioTrack RAW and IEC passthrough output
 * - Probes AC3/DTS/DTS-HD/EAC3/TrueHD passthrough capabilities
 * - Manages audio output lifecycle with keep-alive
 *
 * Reference: xbmc-master/xbmc/cores/AudioEngine/Sinks/AESinkAUDIOTRACK.cpp
 */
class XbmcAudioManager(private val context: Context) {

    data class AudioCapabilities(
        val hasSpeaker: Boolean,
        val hasHdmiOutput: Boolean,
        val hasSpdifOutput: Boolean,
        val hasBluetoothOutput: Boolean,
        val supportsFloat: Boolean,
        val supportsMultiChannelFloat: Boolean,
        val supportedSampleRates: Set<Int>,
        val supportsAC3: Boolean,
        val supportsEAC3: Boolean,
        val supportsDTS: Boolean,
        val supportsDTSHD: Boolean,
        val supportsTrueHD: Boolean,
        val supportsIEC61937: Boolean,
        val nativeSampleRate: Int,
    )

    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private var settings: XbmcAudioSettings = XbmcAudioSettings.load(context)
    private var cachedCapabilities: AudioCapabilities? = null

    // ---- Speaker / Output Detection ----

    /**
     * Detect if the device has a built-in speaker.
     * Android TV boxes typically don't have speakers.
     * Uses PackageManager feature and AudioDeviceInfo enumeration.
     */
    fun hasBuiltInSpeaker(): Boolean {
        // Method 1: PackageManager feature check
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

        // Method 2: Enumerate audio output devices
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        var hasSpeaker = false
        var hasHdmi = false
        var hasSpdif = false
        var hasBluetooth = false

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC -> hasHdmi = true
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> hasSpdif = true
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> hasBluetooth = true
            }
        }

        FileLogger.log(TAG, "Speaker detection: feature=$hasFeature, speaker=$hasSpeaker, " +
                "hdmi=$hasHdmi, spdif=$hasSpdif, bt=$hasBluetooth")

        return hasSpeaker
    }

    /**
     * Probe full audio capabilities of this device.
     * Mirrors Kodi's EnumerateDevicesEx + UpdateAvailablePCMCapabilities
     * + UpdateAvailablePassthroughCapabilities
     */
    fun probeCapabilities(): AudioCapabilities {
        cachedCapabilities?.let { return it }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        var hasSpeaker = false
        var hasHdmi = false
        var hasSpdif = false
        var hasBluetooth = false

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> hasSpeaker = true
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC -> hasHdmi = true
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> hasSpdif = true
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> hasBluetooth = true
            }
        }

        // Native sample rate (like Kodi's getNativeOutputSampleRate)
        val nativeRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val nativeSampleRate = nativeRateStr?.toIntOrNull() ?: 48000

        // Probe supported sample rates (mirrors Kodi's UpdateAvailablePCMCapabilities)
        val supportedRates = mutableSetOf<Int>()
        supportedRates.add(nativeSampleRate)
        val testRates = intArrayOf(32000, 44100, 48000, 88200, 96000, 176400, 192000)
        for (rate in testRates) {
            if (verifySinkConfig(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)) {
                supportedRates.add(rate)
            }
        }

        // Float support
        val supportsFloat = verifySinkConfig(
            nativeSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val supportsMultiChannelFloat = verifySinkConfig(
            nativeSampleRate, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_PCM_FLOAT
        )

        // Passthrough probing (mirrors Kodi's UpdateAvailablePassthroughCapabilities)
        val supportsAC3 = verifySinkConfig(
            48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_AC3
        )
        val supportsEAC3 = verifySinkConfig(
            48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_E_AC3
        )
        val supportsDTS = verifySinkConfig(
            48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_DTS
        )
        val supportsDTSHD = if (Build.VERSION.SDK_INT >= 23) {
            verifySinkConfig(
                48000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_DTS_HD
            )
        } else false
        val supportsTrueHD = if (Build.VERSION.SDK_INT >= 25 && 192000 in supportedRates) {
            verifySinkConfig(
                192000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_DOLBY_TRUEHD
            )
        } else false

        // IEC61937 support (Android 7.0+)
        val supportsIEC = if (Build.VERSION.SDK_INT >= 24) {
            verifySinkConfig(
                48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_IEC61937
            )
        } else false

        val caps = AudioCapabilities(
            hasSpeaker = hasSpeaker,
            hasHdmiOutput = hasHdmi,
            hasSpdifOutput = hasSpdif,
            hasBluetoothOutput = hasBluetooth,
            supportsFloat = supportsFloat,
            supportsMultiChannelFloat = supportsMultiChannelFloat,
            supportedSampleRates = supportedRates,
            supportsAC3 = supportsAC3,
            supportsEAC3 = supportsEAC3,
            supportsDTS = supportsDTS,
            supportsDTSHD = supportsDTSHD,
            supportsTrueHD = supportsTrueHD,
            supportsIEC61937 = supportsIEC,
            nativeSampleRate = nativeSampleRate,
        )

        FileLogger.log(TAG, "Audio capabilities: $caps")
        cachedCapabilities = caps
        return caps
    }

    /**
     * Check if an AudioTrack with the given parameters can be created.
     * Mirrors Kodi's VerifySinkConfiguration.
     */
    private fun verifySinkConfig(sampleRate: Int, channelMask: Int, encoding: Int): Boolean {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            val supported = minBuf > 0
            if (supported) {
                Log.d(TAG, "Sink OK: rate=$sampleRate mask=$channelMask enc=$encoding minBuf=$minBuf")
            }
            supported
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Determine if we should use xbmc-style audio (no built-in speaker, HDMI/SPDIF output).
     */
    fun shouldUseXbmcAudio(): Boolean {
        val caps = probeCapabilities()
        return !caps.hasSpeaker && (caps.hasHdmiOutput || caps.hasSpdifOutput)
    }

    // ---- Audio Output ----

    /**
     * Create and start an AudioTrack for PCM playback.
     * Uses settings from XbmcAudioSettings.
     */
    fun createAudioTrack(sampleRate: Int, channelCount: Int, bitsPerSample: Int): AudioTrack? {
        settings = XbmcAudioSettings.load(context)

        val channelMask = when {
            channelCount >= 8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            channelCount >= 6 -> AudioFormat.CHANNEL_OUT_5POINT1
            channelCount == 1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val encoding = when {
            bitsPerSample >= 24 && probeCapabilities().supportsFloat ->
                AudioFormat.ENCODING_PCM_FLOAT
            bitsPerSample >= 24 -> AudioFormat.ENCODING_PCM_16BIT  // downmix
            bitsPerSample == 16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            FileLogger.error(TAG, "Cannot create AudioTrack: minBuf=$minBuf")
            return null
        }

        return try {
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
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4) // 4x minimum for smooth playback
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            FileLogger.log(TAG, "AudioTrack created: rate=$sampleRate ch=$channelCount " +
                    "enc=$encoding bufSize=${minBuf * 4}")
            track
        } catch (e: Exception) {
            FileLogger.error(TAG, "Failed to create AudioTrack", e)
            null
        }
    }

    fun getSettings(): XbmcAudioSettings = settings

    fun updateSettings(newSettings: XbmcAudioSettings) {
        settings = newSettings
        XbmcAudioSettings.save(context, newSettings)
        FileLogger.log(TAG, "Settings updated: $newSettings")
    }

    fun getOutputDeviceNames(): List<String> {
        val list = mutableListOf<String>()
        list.add(XbmcAudioSettings.AudioOutputDevice.AUDIOTRACK_RAW.displayName)
        if (probeCapabilities().supportsIEC61937) {
            list.add(XbmcAudioSettings.AudioOutputDevice.ANDROID_IEC.displayName)
            list.add(XbmcAudioSettings.AudioOutputDevice.AUDIOTRACK_RAW_IEC.displayName)
        }
        return list
    }

    /**
     * Returns a human-readable summary of audio capabilities for display.
     */
    fun getCapabilitiesSummary(): String {
        val caps = probeCapabilities()
        return buildString {
            appendLine("Built-in speaker: ${if (caps.hasSpeaker) "Yes" else "No"}")
            appendLine("HDMI output: ${if (caps.hasHdmiOutput) "Yes" else "No"}")
            appendLine("SPDIF output: ${if (caps.hasSpdifOutput) "Yes" else "No"}")
            appendLine("Bluetooth: ${if (caps.hasBluetoothOutput) "Yes" else "No"}")
            appendLine("Native sample rate: ${caps.nativeSampleRate} Hz")
            appendLine("Sample rates: ${caps.supportedSampleRates.sorted().joinToString(", ")}")
            appendLine("Float PCM: ${if (caps.supportsFloat) "Yes" else "No"}")
            appendLine("Multi-ch Float: ${if (caps.supportsMultiChannelFloat) "Yes" else "No"}")
            appendLine("--- Passthrough ---")
            appendLine("IEC61937: ${if (caps.supportsIEC61937) "Yes" else "No"}")
            appendLine("AC3 (Dolby Digital): ${if (caps.supportsAC3) "Yes" else "No"}")
            appendLine("EAC3 (Dolby Digital+): ${if (caps.supportsEAC3) "Yes" else "No"}")
            appendLine("DTS: ${if (caps.supportsDTS) "Yes" else "No"}")
            appendLine("DTS-HD: ${if (caps.supportsDTSHD) "Yes" else "No"}")
            append("TrueHD: ${if (caps.supportsTrueHD) "Yes" else "No"}")
        }
    }
}
