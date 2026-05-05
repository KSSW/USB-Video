package com.meta.usbvideo.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Audio settings inspired by Kodi/XBMC AudioEngine.
 * Persisted via SharedPreferences.
 */
data class XbmcAudioSettings(
    // Audio output device
    var outputDevice: AudioOutputDevice = AudioOutputDevice.AUDIOTRACK_RAW,
    // Number of channels: 2.0, 5.1, 7.1
    var channelCount: ChannelConfig = ChannelConfig.STEREO_2_0,
    // Output configuration
    var outputConfig: OutputConfig = OutputConfig.OPTIMIZED,
    // Volume control steps (1-100)
    var volumeSteps: Int = 90,
    // Keep original volume on downmix
    var keepOriginalVolume: Boolean = true,
    // Stereo upmix
    var stereoUpmix: Boolean = false,
    // Resample quality
    var resampleQuality: ResampleQuality = ResampleQuality.MEDIUM,
    // Keep audio device alive timeout (minutes, 0 = disabled)
    var keepAliveMinutes: Int = 1,
    // Send low volume noise to keep device active
    var sendLowVolumeNoise: Boolean = true,
    // Play UI sounds
    var playUiSounds: UiSoundMode = UiSoundMode.WHEN_PLAYBACK_STOPPED,

    // ---- Passthrough settings ----
    var enablePassthrough: Boolean = false,
    // Passthrough output device
    var passthroughDevice: AudioOutputDevice = AudioOutputDevice.AUDIOTRACK_RAW,
    // Dolby Digital (AC3) compatible playback
    var enableAC3: Boolean = false,
    // Enable Dolby Digital (AC3) transcoding
    var enableAC3Transcoding: Boolean = false,
    // DTS compatible playback
    var enableDTS: Boolean = false,
    // DTS-HD compatible playback
    var enableDTSHD: Boolean = false,
) {
    enum class AudioOutputDevice(val displayName: String) {
        AUDIOTRACK_RAW("AudioTrack (RAW)"),
        ANDROID_IEC("Android IEC packer"),
        AUDIOTRACK_RAW_IEC("AudioTrack (RAW), Android IEC packer"),
    }

    enum class ChannelConfig(val displayName: String) {
        STEREO_2_0("2.0"),
        SURROUND_5_1("5.1"),
        SURROUND_7_1("7.1"),
    }

    enum class OutputConfig(val displayName: String) {
        OPTIMIZED("Optimized"),
        FIXED("Fixed"),
        BEST_MATCH("Best Match"),
    }

    enum class ResampleQuality(val displayName: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
    }

    enum class UiSoundMode(val displayName: String) {
        ALWAYS("Always"),
        WHEN_PLAYBACK_STOPPED("When playback stopped"),
        NEVER("Never"),
    }

    companion object {
        private const val PREFS_NAME = "xbmc_audio_settings"

        fun load(context: Context): XbmcAudioSettings {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return XbmcAudioSettings(
                outputDevice = enumValueOrDefault(p.getString("outputDevice", null), AudioOutputDevice.AUDIOTRACK_RAW),
                channelCount = enumValueOrDefault(p.getString("channelCount", null), ChannelConfig.STEREO_2_0),
                outputConfig = enumValueOrDefault(p.getString("outputConfig", null), OutputConfig.OPTIMIZED),
                volumeSteps = p.getInt("volumeSteps", 90),
                keepOriginalVolume = p.getBoolean("keepOriginalVolume", true),
                stereoUpmix = p.getBoolean("stereoUpmix", false),
                resampleQuality = enumValueOrDefault(p.getString("resampleQuality", null), ResampleQuality.MEDIUM),
                keepAliveMinutes = p.getInt("keepAliveMinutes", 1),
                sendLowVolumeNoise = p.getBoolean("sendLowVolumeNoise", true),
                playUiSounds = enumValueOrDefault(p.getString("playUiSounds", null), UiSoundMode.WHEN_PLAYBACK_STOPPED),
                enablePassthrough = p.getBoolean("enablePassthrough", false),
                passthroughDevice = enumValueOrDefault(p.getString("passthroughDevice", null), AudioOutputDevice.AUDIOTRACK_RAW),
                enableAC3 = p.getBoolean("enableAC3", false),
                enableAC3Transcoding = p.getBoolean("enableAC3Transcoding", false),
                enableDTS = p.getBoolean("enableDTS", false),
                enableDTSHD = p.getBoolean("enableDTSHD", false),
            )
        }

        fun save(context: Context, settings: XbmcAudioSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString("outputDevice", settings.outputDevice.name)
                putString("channelCount", settings.channelCount.name)
                putString("outputConfig", settings.outputConfig.name)
                putInt("volumeSteps", settings.volumeSteps)
                putBoolean("keepOriginalVolume", settings.keepOriginalVolume)
                putBoolean("stereoUpmix", settings.stereoUpmix)
                putString("resampleQuality", settings.resampleQuality.name)
                putInt("keepAliveMinutes", settings.keepAliveMinutes)
                putBoolean("sendLowVolumeNoise", settings.sendLowVolumeNoise)
                putString("playUiSounds", settings.playUiSounds.name)
                putBoolean("enablePassthrough", settings.enablePassthrough)
                putString("passthroughDevice", settings.passthroughDevice.name)
                putBoolean("enableAC3", settings.enableAC3)
                putBoolean("enableAC3Transcoding", settings.enableAC3Transcoding)
                putBoolean("enableDTS", settings.enableDTS)
                putBoolean("enableDTSHD", settings.enableDTSHD)
                apply()
            }
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T {
            return if (name != null) {
                try { enumValueOf<T>(name) } catch (_: Exception) { default }
            } else default
        }
    }
}
