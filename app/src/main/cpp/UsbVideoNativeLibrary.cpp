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

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <memory.h>
#include <string>

#include "UsbAudioStreamer.h"
#include "UsbVideoStreamer.h"
#include "clog.h"

static JavaVM* javaVM_ = nullptr;

static std::unique_ptr<UsbAudioStreamer> streamer_{};
static std::unique_ptr<UsbVideoStreamer> uvcStreamer_{};
static std::string lastRecordingError_{};

using ANativeWindowOwner = std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)>;
static ANativeWindowOwner previewWindow_ = ANativeWindowOwner(nullptr, &ANativeWindow_release);

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved) {
  javaVM_ = jvm;
  JNIEnv* env;
  if (JNI_OK != jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4)) {
    CLOGE("Get JNIEnv failed");
    return JNI_ERR;
  }
  CLOGI("JNI_OnLoad success!");
  return JNI_VERSION_1_4;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* jvm, void* reserved) {
  if (jvm) {
    jvm->DestroyJavaVM();
  }
  javaVM_ = nullptr;
  CLOGI("JNI_OnUnload success!");
}

JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getUsbDeviceSpeed(JNIEnv* env, jobject self) {
  if (streamer_ != nullptr) {
    return streamer_->getUsbDeviceSpeed();
  }
  return 0; /* LIBUSB_SPEED_UNKNOWN */
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_connectUsbVideoStreamingNative(
    JNIEnv* env,
    jobject tis,
    jint deviceFd,
    jint width,
    jint height,
    jint fps,
    jint libuvcFrameFormat,
    jobject jSurface) {
  CLOGE(
      " Java_com_meta_usbvideo_UsbVideoNativeLibrary__connectUsbVideoStreamingNative called with deviceFd %d",
      deviceFd);
  if (uvcStreamer_ == nullptr) {
    uvcStreamer_ = std::make_unique<UsbVideoStreamer>(
        (intptr_t)deviceFd, width, height, fps, static_cast<uvc_frame_format>(libuvcFrameFormat));
    previewWindow_.reset(ANativeWindow_fromSurface(env, jSurface));
    return uvcStreamer_->configureOutput(previewWindow_.get());
  }
  return false;
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_reconfigureVideoStreamingNative(
    JNIEnv* env,
    jobject self,
    jint width,
    jint height,
    jint fps,
    jint libuvcFrameFormat,
    jobject jSurface) {
  if (uvcStreamer_ == nullptr) {
    return false;
  }
  // Reuse existing ANativeWindow if available; recreating it disconnects the
  // SurfaceTexture's BufferQueue producer and causes the preview to freeze on
  // the last rendered frame even though new frames are still being decoded.
  if (previewWindow_ == nullptr) {
    previewWindow_.reset(ANativeWindow_fromSurface(env, jSurface));
  }
  return uvcStreamer_->reconfigureStream(
      width, height, fps, static_cast<uvc_frame_format>(libuvcFrameFormat), previewWindow_.get());
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_startUsbVideoStreamingNative(
        JNIEnv* env,
        jobject self) {
  if (uvcStreamer_ != nullptr) {
    return uvcStreamer_->start();
  }
  return false;
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_stopUsbVideoStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (uvcStreamer_ != nullptr) {
    uvcStreamer_->stop();
  }
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_disconnectUsbVideoStreamingNative(
        JNIEnv* env,
        jobject self) {
  uvcStreamer_ = nullptr;
  previewWindow_.reset(nullptr);
}

JNIEXPORT jstring JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_streamingStatsSummaryString(
    JNIEnv* env,
    jobject self) {
  std::string result = "";
  if (streamer_ != nullptr) {
    result += streamer_->statsSummaryString();
    result += "\n";
  }
  if (uvcStreamer_ != nullptr) {
    result += uvcStreamer_->statsSummaryString();
  }
  return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_getAudioDiagnostics(
    JNIEnv* env,
    jobject self) {
  std::string result = "";
  if (streamer_ != nullptr) {
    result += "ctrl: " + streamer_->getCtrlDiagLog();
    result += " | hex1: " + streamer_->getRawPktHexDump();
  }
  return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_getUvcXuDiagnostics(
    JNIEnv* env,
    jobject self) {
  std::string result = "";
  if (uvcStreamer_ != nullptr) {
    result = uvcStreamer_->getXuDiagLog();
  }
  return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_getHdmiInfoFrameDiagnostics(
    JNIEnv* env,
    jobject self) {
  std::string result = "";
  if (uvcStreamer_ != nullptr) {
    result = uvcStreamer_->getHdmiInfoFrameLog();
  }
  return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_startRecordingNative(
    JNIEnv* env,
    jobject self,
    jstring path,
    jint containerFormat,
    jint audioSampleRate,
    jint audioChannels,
    jint audioBitsPerSample) {
  lastRecordingError_.clear();
  if (uvcStreamer_ == nullptr) {
    lastRecordingError_ = "USB video streamer is null (video stream not connected)";
    CLOGE("startRecordingNative failed: %s", lastRecordingError_.c_str());
    return JNI_FALSE;
  }
  const char* c_path = env->GetStringUTFChars(path, nullptr);
  if (!c_path) {
    lastRecordingError_ = "Failed to read output path";
    CLOGE("startRecordingNative failed: %s", lastRecordingError_.c_str());
    return JNI_FALSE;
  }
  uvc::ContainerFormat container;
  switch (containerFormat) {
    case 0: container = uvc::ContainerFormat::AVI; break;
    case 1: container = uvc::ContainerFormat::MP4; break;
    case 2: container = uvc::ContainerFormat::MOV; break;
    case 3: container = uvc::ContainerFormat::MKV; break;
    default: container = uvc::ContainerFormat::AUTO; break;
  }
  CLOGI("startRecordingNative: path=%s container=%d audio=%dHz/%dch/%dbit",
        c_path, (int)container, audioSampleRate, audioChannels, audioBitsPerSample);
  bool ret = uvcStreamer_->startRecording(c_path, container,
                                          audioSampleRate, audioChannels, audioBitsPerSample);
  if (!ret) {
    lastRecordingError_ = uvcStreamer_->getLastRecordingError();
    if (lastRecordingError_.empty()) {
      lastRecordingError_ = "Native startRecording returned false";
    }
    CLOGE("startRecordingNative failed: %s", lastRecordingError_.c_str());
  } else {
    CLOGI("startRecordingNative success");
  }
  env->ReleaseStringUTFChars(path, c_path);

  // Bridge audio data from UsbAudioStreamer to the recorder
  if (ret && streamer_ != nullptr) {
    streamer_->setAudioRecordCallback([](const uint8_t* data, size_t len) {
      if (uvcStreamer_ && uvcStreamer_->isRecording()) {
        uvcStreamer_->writeAudioToRecorder(data, len);
      }
    });
  }

  return ret ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getLastErrorNative(JNIEnv* env, jobject self) {
  return env->NewStringUTF(lastRecordingError_.empty() ? "" : lastRecordingError_.c_str());
}

JNIEXPORT void JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_stopRecordingNative(JNIEnv* env, jobject self) {
  // Clear audio recording callback first to stop feeding data
  if (streamer_ != nullptr) {
    streamer_->setAudioRecordCallback(nullptr);
  }
  if (uvcStreamer_ != nullptr) {
    uvcStreamer_->stopRecording();
  }
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_isRecordingNative(JNIEnv* env, jobject self) {
  if (uvcStreamer_ != nullptr) {
    return uvcStreamer_->isRecording() ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_connectUsbAudioStreamingNative(
    JNIEnv* env,
    jobject tis,
    jint deviceFd,
    jint jAudioFormat,
    jint samplingFrequency,
    jint subFrameSize,
    jint channelCount,
    jint outputChannelCount,
    jint jAudioPerfMode,
    jint outputFramesPerBuffer,
    jint desiredInterfaceNumber,
    jint desiredAltSetting,
    jint initialMuteMs) {
  if (streamer_ != nullptr) {
    //CLOGE("startUsbAudioStreamingNative called before stopUsbAudioStreamingNative was called");
    return true;
  }
  streamer_ = std::make_unique<UsbAudioStreamer>(
      (intptr_t)deviceFd,
      jAudioFormat,
      samplingFrequency,
      subFrameSize,
      channelCount,
      outputChannelCount,
      jAudioPerfMode,
      outputFramesPerBuffer,
      desiredInterfaceNumber,
      desiredAltSetting);
  if (streamer_ != nullptr) {
    streamer_->setInitialMuteMs(initialMuteMs);
  }
  return streamer_ != nullptr;
}


JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_disconnectUsbAudioStreamingNative(
        JNIEnv* env,
        jobject self) {
  if (streamer_ != nullptr) {
    streamer_->stop();
    streamer_ = nullptr;
  }
}
JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_startUsbAudioStreamingNative(
        JNIEnv* env,
        jobject self) {
  if (streamer_ != nullptr) {
    streamer_->start();
  }
}

JNIEXPORT void JNICALL Java_com_meta_usbvideo_UsbVideoNativeLibrary_stopUsbAudioStreamingNative(
    JNIEnv* env,
    jobject self) {
  if (streamer_ != nullptr) {
    streamer_->stop();
  }
}

JNIEXPORT jfloatArray JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getNativeAudioLevels(JNIEnv* env, jobject self) {
  int chCount = 2;
  if (streamer_ != nullptr) {
    chCount = streamer_->getChannelCount();
    if (chCount < 2) chCount = 2;
    if (chCount > UsbAudioStreamer::MAX_AUDIO_CHANNELS) chCount = UsbAudioStreamer::MAX_AUDIO_CHANNELS;
  }
  jfloatArray result = env->NewFloatArray(chCount);
  if (result == nullptr) return nullptr;
  float levels[UsbAudioStreamer::MAX_AUDIO_CHANNELS] = {};
  if (streamer_ != nullptr) {
    const float* src = streamer_->getAudioLevels();
    for (int i = 0; i < chCount; i++) {
      levels[i] = src[i];
    }
  }
  env->SetFloatArrayRegion(result, 0, chCount, levels);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getNativeVideoFps(JNIEnv* env, jobject self) {
  if (uvcStreamer_ != nullptr) {
    return uvcStreamer_->getFps();
  }
  return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_nativeLogToJava(
    JNIEnv* env,
    jobject self,
    jstring tag,
    jstring message) {
  if (!tag || !message) {
    return JNI_FALSE;
  }
  const char* tagStr = env->GetStringUTFChars(tag, nullptr);
  const char* msgStr = env->GetStringUTFChars(message, nullptr);
  
  // Call FileLogger via reflection
  jclass fileLoggerClass = env->FindClass("com/meta/usbvideo/util/FileLogger");
  if (fileLoggerClass) {
    jmethodID logMethod = env->GetStaticMethodID(fileLoggerClass, "log", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (logMethod) {
      env->CallStaticVoidMethod(fileLoggerClass, logMethod, tag, message);
    }
    env->DeleteLocalRef(fileLoggerClass);
  }
  
  env->ReleaseStringUTFChars(tag, tagStr);
  env->ReleaseStringUTFChars(message, msgStr);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_setRecorderLogger(JNIEnv* env, jobject self) {
  // JNI logging bridge disabled - RawRecorder logs to logcat only
}

JNIEXPORT jlongArray JNICALL
Java_com_meta_usbvideo_UsbVideoNativeLibrary_getRecordingStatsNative(JNIEnv* env, jobject self) {
  jlongArray result = env->NewLongArray(4);
  jlong stats[4] = {0, 0, 0, 0};
  if (uvcStreamer_ != nullptr) {
    uvcStreamer_->getRecordingStats(&stats[0], &stats[1], &stats[2], &stats[3]);
  }
  env->SetLongArrayRegion(result, 0, 4, stats);
  return result;
}

} // extern "C"
