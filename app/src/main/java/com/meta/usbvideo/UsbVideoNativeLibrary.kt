/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.meta.usbvideo

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import android.view.Surface
import com.meta.usbvideo.usb.AudioStreamingConnection
import com.meta.usbvideo.usb.AudioStreamingFormatTypeDescriptor
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoStreamingConnection
import com.meta.usbvideo.usb.UsbMonitor

enum class UsbSpeed {
  Unknown,
  Low,
  Full,
  High,
  Super,
}

object UsbVideoNativeLibrary {

  fun getUsbSpeed(): UsbSpeed = UsbSpeed.values()[getUsbDeviceSpeed()]

  private external fun getUsbDeviceSpeed(): Int

  fun connectUsbAudioStreaming(
      context: Context,
      audioStreamingConnection: AudioStreamingConnection,
      initialMuteMs: Int = 3000,
  ): Pair<Boolean, String> {
    if (!audioStreamingConnection.supportsAudioStreaming) {
      return false to "No Audio Streaming Interface"
    }

    if (!audioStreamingConnection.hasSupportedAudioFormat) {
      return false to "No Supported Audio Format"
    }

    if (!audioStreamingConnection.hasFormatTypeDescriptor) {
      return false to "No Audio Streaming Format Descriptor"
    }

    val format: AudioStreamingFormatTypeDescriptor = audioStreamingConnection.formatTypeDescriptor

    val channelCount = format.bNrChannels
    val samplingFrequency = format.tSamFreq.firstOrNull() ?: return false to "No Sample Rate"
    val subFrameSize = format.bSubFrameSize

    // Always pass PCM_16BIT to native layer; C++ will convert 24-bit → 16-bit
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val outputFramesPerBuffer =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 0

    val deviceFD = audioStreamingConnection.deviceFD
    val desiredIfaceNum = if (audioStreamingConnection.hasInterfaceDescriptor)
        audioStreamingConnection.interfaceDescriptor.bInterfaceNumber else -1
    val desiredAltSetting = if (audioStreamingConnection.hasInterfaceDescriptor)
        audioStreamingConnection.interfaceDescriptor.bAlternateSetting else -1

    // Force stereo output for U4 4K60 device (downmix from multi-channel)
    val outputChannelCount = if (UsbMonitor.forceStereoMode) 2 else channelCount

    Log.i("UsbVideoNativeLibrary", "connectUsbAudioStreaming: ch=$channelCount, " +
        "outputCh=$outputChannelCount, rate=$samplingFrequency, subFrame=$subFrameSize, iface=$desiredIfaceNum, alt=$desiredAltSetting")

    return if (connectUsbAudioStreamingNative(
        deviceFD,
        audioFormat,
        samplingFrequency,
        subFrameSize,
        channelCount,  // USB input channel count (may be 6ch)
        outputChannelCount,  // Output channel count (forced to 2 for U4 4K60)
        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
        outputFramesPerBuffer,
        desiredIfaceNum,
        desiredAltSetting,
        initialMuteMs,
    )) {
      true to "Success"
    } else {
      false to "Native audio player failure. Check logs for errors."
    }
  }

  private external fun connectUsbAudioStreamingNative(
      deviceFD: Int,
      jAudioFormat: Int,
      samplingFrequency: Int,
      subFrameSize: Int,
      channelCount: Int,
      outputChannelCount: Int,
      jAudioPerfMode: Int,
      outputFramesPerBuffer: Int,
      desiredInterfaceNumber: Int,
      desiredAltSetting: Int,
      initialMuteMs: Int,
  ): Boolean

  external fun disconnectUsbAudioStreamingNative()

  external fun startUsbAudioStreamingNative()

  external fun stopUsbAudioStreamingNative()

  external fun getNativeAudioLevels(): FloatArray

  fun connectUsbVideoStreaming(
      videoStreamingConnection: VideoStreamingConnection,
      surface: Surface,
      frameFormat: VideoFormat?,
  ): Pair<Boolean, String> {
    val videoFormat = frameFormat ?: return false to "No supported video format"
    val deviceFD = videoStreamingConnection.deviceFD
    return if (connectUsbVideoStreamingNative(
        deviceFD,
        videoFormat.width,
        videoFormat.height,
        videoFormat.fps,
        videoFormat.toLibuvcFrameFormat().ordinal,
        surface,
    )) {
      true to "Success"
    } else {
      false to "Native video player failure. Check logs for errors."
    }
  }

  fun reconfigureVideoStreaming(
      surface: Surface,
      frameFormat: VideoFormat?,
  ): Pair<Boolean, String> {
    val videoFormat = frameFormat ?: return false to "No supported video format"
    return if (reconfigureVideoStreamingNative(
        videoFormat.width,
        videoFormat.height,
        videoFormat.fps,
        videoFormat.toLibuvcFrameFormat().ordinal,
        surface,
    )) {
      true to "Success"
    } else {
      false to "Native video player failure. Check logs for errors."
    }
  }

  external fun connectUsbVideoStreamingNative(
    deviceFD: Int,
    width: Int,
    height: Int,
    fps: Int,
    libuvcFrameFormat: Int,
    surface: Surface,
  ): Boolean
  external fun reconfigureVideoStreamingNative(
    width: Int,
    height: Int,
    fps: Int,
    libuvcFrameFormat: Int,
    surface: Surface,
  ): Boolean
  external fun startUsbVideoStreamingNative(): Boolean
  external fun stopUsbVideoStreamingNative()
  external fun disconnectUsbVideoStreamingNative()

  external fun streamingStatsSummaryString(): String
  external fun getAudioDiagnostics(): String
  external fun getUvcXuDiagnostics(): String
  external fun getHdmiInfoFrameDiagnostics(): String
  external fun getNativeVideoFps(): Int

  fun startRecording(
      path: String,
      containerFormat: com.meta.usbvideo.record.ContainerFormat,
      audioSampleRate: Int = 0,
      audioChannels: Int = 0,
      audioBitsPerSample: Int = 0,
  ): Boolean {
    return startRecordingNative(path, containerFormat.ordinal, audioSampleRate, audioChannels, audioBitsPerSample)
  }

  external fun startRecordingNative(
      path: String,
      containerFormat: Int,
      audioSampleRate: Int,
      audioChannels: Int,
      audioBitsPerSample: Int,
  ): Boolean

  external fun stopRecordingNative()
  external fun isRecordingNative(): Boolean
  external fun getLastErrorNative(): String

  /** Get recording stats: [videoFramesWritten, audioSamplesWritten, droppedVideoCount, recordingFileSizeBytes] */
  external fun getRecordingStatsNative(): LongArray

  // Native logging interface to FileLogger
  external fun nativeLogToJava(tag: String, message: String): Boolean
  external fun setRecorderLogger()
}
