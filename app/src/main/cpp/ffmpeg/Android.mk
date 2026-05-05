# Android.mk for FFmpeg static libraries
# Prebuilt static libraries for four ABIs.
# Include this after setting LOCAL_PATH in your parent makefile.

FFMPEG_LOCAL_PATH := $(call my-dir)

# Map APP_ABI to ffmpeg subdirectory
ifeq ($(APP_ABI),arm64-v8a)
  FFMPEG_ABI := arm64-v8a
else ifeq ($(APP_ABI),armeabi-v7a)
  FFMPEG_ABI := armeabi-v7a
else ifeq ($(APP_ABI),x86)
  FFMPEG_ABI := x86
else ifeq ($(APP_ABI),x86_64)
  FFMPEG_ABI := x86_64
else
  $(error Unsupported APP_ABI: $(APP_ABI))
endif

# Prebuilt: libavutil.a
include $(CLEAR_VARS)
LOCAL_MODULE    := ffmpeg-avutil
LOCAL_SRC_FILES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/lib/libavutil.a
LOCAL_EXPORT_C_INCLUDES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/include
include $(PREBUILT_STATIC_LIBRARY)

# Prebuilt: libavcodec.a
include $(CLEAR_VARS)
LOCAL_MODULE    := ffmpeg-avcodec
LOCAL_SRC_FILES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/lib/libavcodec.a
LOCAL_EXPORT_C_INCLUDES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/include
include $(PREBUILT_STATIC_LIBRARY)

# Prebuilt: libavformat.a
include $(CLEAR_VARS)
LOCAL_MODULE    := ffmpeg-avformat
LOCAL_SRC_FILES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/lib/libavformat.a
LOCAL_EXPORT_C_INCLUDES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/include
include $(PREBUILT_STATIC_LIBRARY)

# Prebuilt: libswresample.a (optional, pulled in by some codec paths)
include $(CLEAR_VARS)
LOCAL_MODULE    := ffmpeg-swresample
LOCAL_SRC_FILES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/lib/libswresample.a
LOCAL_EXPORT_C_INCLUDES := $(FFMPEG_LOCAL_PATH)/$(FFMPEG_ABI)/include
include $(PREBUILT_STATIC_LIBRARY)
