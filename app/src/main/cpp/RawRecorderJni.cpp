/*
 * RawRecorderJni.cpp
 *
 * JNI bridge for RawRecorder FFmpeg-based raw recording.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <pthread.h>
#include "RawRecorder.h"

using namespace uvc;

#define LOG_TAG "RawRecorderJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global recorder instance (for now, single instance per process)
static RawRecorder *g_recorder = nullptr;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

// Maps for enum conversions
static ContainerFormat intToContainerFormat(jint fmt) {
    switch (fmt) {
        case 0: return ContainerFormat::AVI;
        case 1: return ContainerFormat::MP4;
        case 2: return ContainerFormat::MOV;
        case 3: return ContainerFormat::MKV;
        default: return ContainerFormat::AUTO;
    }
}

static VideoSourceFormat intToVideoSourceFormat(jint fmt) {
    switch (fmt) {
        case 0: return VideoSourceFormat::MJPEG;
        case 1: return VideoSourceFormat::YUYV;
        case 2: return VideoSourceFormat::UYVY;
        case 3: return VideoSourceFormat::NV12;
        case 4: return VideoSourceFormat::NV21;
        case 5: return VideoSourceFormat::BGR24;
        case 6: return VideoSourceFormat::RGB24;
        default: return VideoSourceFormat::UNKNOWN;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeCreate(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_mutex);
    if (!g_recorder) {
        g_recorder = new RawRecorder();
        LOGI("Created RawRecorder instance");
    }
    pthread_mutex_unlock(&g_mutex);
    return reinterpret_cast<jlong>(g_recorder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    pthread_mutex_lock(&g_mutex);
    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (recorder) {
        recorder->stop();
        if (recorder == g_recorder) {
            delete g_recorder;
            g_recorder = nullptr;
            LOGI("Destroyed RawRecorder instance");
        }
    }
    pthread_mutex_unlock(&g_mutex);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeStart(
        JNIEnv *env, jclass clazz, jlong handle,
        jstring path, jint width, jint height, jint fps,
        jint srcFormat, jint containerFormat,
        jint audioSampleRate, jint audioChannels, jint audioBitsPerSample) {

    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) return -1;

    const char *c_path = env->GetStringUTFChars(path, nullptr);
    if (!c_path) return -1;

    VideoSourceFormat srcFmt = intToVideoSourceFormat(srcFormat);
    ContainerFormat container = intToContainerFormat(containerFormat);

    int ret = recorder->start(c_path, width, height, fps, srcFmt, container,
                              audioSampleRate, audioChannels, audioBitsPerSample);

    env->ReleaseStringUTFChars(path, c_path);

    if (ret == 0) {
        LOGI("Started recording: %dx%d@%d, src=%d, container=%d", width, height, fps, srcFormat, containerFormat);
    } else {
        LOGE("Failed to start recording: %d", ret);
    }

    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeStop(JNIEnv *env, jclass clazz, jlong handle) {
    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (recorder) {
        recorder->stop();
        LOGI("Stopped recording");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeWriteFrame(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray data, jlong pts) {

    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) return -1;

    jsize len = env->GetArrayLength(data);
    if (len <= 0) return -1;

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int ret = recorder->writeFrame(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(len),
        static_cast<int64_t>(pts)
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeIsRecording(JNIEnv *env, jclass clazz, jlong handle) {
    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) return JNI_FALSE;
    return recorder->isRecording() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeGetLastError(JNIEnv *env, jclass clazz, jlong handle) {
    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) {
        return env->NewStringUTF("Null recorder handle");
    }
    return env->NewStringUTF(recorder->getLastError());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeSetupAudio(
        JNIEnv *env, jclass clazz, jlong handle,
        jint sampleRate, jint channels, jint bitsPerSample) {

    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) return -1;

    int ret = recorder->setupAudioStream(sampleRate, channels, bitsPerSample);

    if (ret == 0) {
        LOGI("Audio stream setup: sampleRate=%d, channels=%d, bits=%d",
             sampleRate, channels, bitsPerSample);
    } else {
        LOGE("Failed to setup audio stream: %d", ret);
    }

    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_meta_usbvideo_record_RawRecorder_nativeWriteAudio(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray data, jlong pts) {

    RawRecorder *recorder = reinterpret_cast<RawRecorder*>(handle);
    if (!recorder) return -1;

    jsize len = env->GetArrayLength(data);
    if (len <= 0) return -1;

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int ret = recorder->writeAudio(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(len),
        static_cast<int64_t>(pts)
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    return ret;
}
