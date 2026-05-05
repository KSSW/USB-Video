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

#include "UsbVideoStreamer.h"

#include <android/bitmap.h>
#include <android/data_space.h>
#if __ANDROID_MIN_SDK_VERSION__ >= 30
#include <android/imagedecoder.h>
#endif
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <unistd.h>
#include <libyuv.h>
#include <libyuv/convert_argb.h>
#include <libyuv/convert_from_argb.h>
#include <libyuv/planar_functions.h>

#include <chrono>
#include <format>

#include <memory.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cstring>

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbVideoStreamer", __VA_ARGS__)

#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbVideoStreamer", __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbVideoStreamer", __VA_ARGS__)

#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbVideoStreamer", __VA_ARGS__)

UsbVideoStreamer::UsbVideoStreamer(
    intptr_t deviceFD,
    int32_t width,
    int32_t height,
    int32_t fps,
    uvc_frame_format uvcFrameFormat)
    : deviceFD_(deviceFD),
      width_(width),
      height_(height),
      fps_(fps),
      uvcFrameFormat_(uvcFrameFormat) {
  if (libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY) != LIBUSB_SUCCESS) {
    ULOGE("libusb setting no discovery option failed");
  }

  // Initialize a UVC service context. Libuvc will set up its own libusb context.
  uvc_error_t res = uvc_init(&uvcContext_, nullptr);
  if (res != UVC_SUCCESS) {
    ULOGE("uvc_init failed %s", uvc_strerror(res));
    return;
  }
  ULOGE("UVC initialized");

  if ((uvc_wrap(deviceFD, uvcContext_, &deviceHandle_) != UVC_SUCCESS) ||
      (deviceHandle_ == nullptr)) {
    ULOGE("uvc_wrap error");
    return;
  }

  // Enumerate UVC Extension Units to check for audio-related capabilities
  enumerateExtensionUnits();

  res = uvc_get_stream_ctrl_format_size(
      deviceHandle_,
      &streamCtrl_, /* result stored in ctrl */
      uvcFrameFormat_,
      width,
      height,
      fps);
  if (res == UVC_SUCCESS) {
    captureFrameWidth_ = width;
    captureFrameHeight_ = height;
    captureFrameFps_ = fps;
    captureFrameFormat_ = uvcFrameFormat_;
    isStreamControlNegotiated_ = true;
    ULOGI(
        "uvc_get_stream_ctrl_format_size found for %dx%d@%dfps format: %d",
        width,
        height,
        fps,
        uvcFrameFormat_);
  } else {
    isStreamControlNegotiated_ = false;
    ULOGE(
        "uvc_get_stream_ctrl_format_size for %d %dx%d@%dfps failed %s",
        uvcFrameFormat_,
        width,
        height,
        fps,
        uvc_strerror(res));
  }
}

void UsbVideoStreamer::enumerateExtensionUnits() {
  if (!deviceHandle_ || !deviceHandle_->info) {
    xuDiagLog_ = "No device info";
    return;
  }

  std::string log;
  auto* info = deviceHandle_->info;

  // Log Input Terminals
  const uvc_input_terminal_t* it = uvc_get_input_terminals(deviceHandle_);
  int itCount = 0;
  while (it) {
    char buf[256];
    snprintf(buf, sizeof(buf), "IT[%d] id=%d type=0x%04x\n", itCount, it->bTerminalID, it->wTerminalType);
    log += buf;
    it = it->next;
    itCount++;
  }

  // Log Processing Units
  const uvc_processing_unit_t* pu = uvc_get_processing_units(deviceHandle_);
  int puCount = 0;
  while (pu) {
    char buf[256];
    snprintf(buf, sizeof(buf), "PU[%d] id=%d srcId=%d controls=0x%04llx\n",
             puCount, pu->bUnitID, pu->bSourceID, (unsigned long long)pu->bmControls);
    log += buf;
    pu = pu->next;
    puCount++;
  }

  // Log Extension Units with GUIDs
  const uvc_extension_unit_t* xu = uvc_get_extension_units(deviceHandle_);
  int xuCount = 0;
  while (xu) {
    char guidStr[64];
    snprintf(guidStr, sizeof(guidStr),
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             xu->guidExtensionCode[3], xu->guidExtensionCode[2],
             xu->guidExtensionCode[1], xu->guidExtensionCode[0],
             xu->guidExtensionCode[5], xu->guidExtensionCode[4],
             xu->guidExtensionCode[7], xu->guidExtensionCode[6],
             xu->guidExtensionCode[8], xu->guidExtensionCode[9],
             xu->guidExtensionCode[10], xu->guidExtensionCode[11],
             xu->guidExtensionCode[12], xu->guidExtensionCode[13],
             xu->guidExtensionCode[14], xu->guidExtensionCode[15]);

    char buf[512];
    snprintf(buf, sizeof(buf), "XU[%d] id=%d guid={%s} controls=0x%04llx\n",
             xuCount, xu->bUnitID, guidStr, (unsigned long long)xu->bmControls);
    log += buf;
    xu = xu->next;
    xuCount++;
  }

  // Also dump all USB interface classes for completeness
  if (info->config) {
    log += "--- USB Interfaces ---\n";
    for (int i = 0; i < info->config->bNumInterfaces; i++) {
      const auto& iface = info->config->interface[i];
      for (int a = 0; a < iface.num_altsetting; a++) {
        const auto& alt = iface.altsetting[a];
        char buf[256];
        snprintf(buf, sizeof(buf), "iface%d alt%d class=0x%02x sub=0x%02x proto=0x%02x eps=%d extra=%d\n",
                 alt.bInterfaceNumber, alt.bAlternateSetting,
                 alt.bInterfaceClass, alt.bInterfaceSubClass,
                 alt.bInterfaceProtocol, alt.bNumEndpoints, alt.extra_length);
        log += buf;

        // For each endpoint, log its details
        for (int e = 0; e < alt.bNumEndpoints; e++) {
          const auto& ep = alt.endpoint[e];
          snprintf(buf, sizeof(buf), "  ep[%d] addr=0x%02x attr=0x%02x maxPkt=%d interval=%d\n",
                   e, ep.bEndpointAddress, ep.bmAttributes,
                   ep.wMaxPacketSize, ep.bInterval);
          log += buf;
        }
      }
    }
  }

  log += "\n--- UVC Video Streaming Extra ---\n";
  // Also dump raw UVC Video Streaming extra bytes to identify format
  if (info->config) {
    for (int i = 0; i < info->config->bNumInterfaces; i++) {
      const auto& iface = info->config->interface[i];
      for (int a = 0; a < iface.num_altsetting; a++) {
        const auto& alt = iface.altsetting[a];
        if (alt.bInterfaceClass == 14 && alt.bInterfaceSubClass == 2 && alt.extra_length > 0) {
          char header[128];
          snprintf(header, sizeof(header), "VS extra[%d:%d] class=0x%02x sub=0x%02x len=%d hex:\n",
                   i, a, alt.bInterfaceClass, alt.bInterfaceSubClass, alt.extra_length);
          log += header;
          for (int b = 0; b < alt.extra_length && b < 1024; b++) {
            char buf[8];
            snprintf(buf, sizeof(buf), "%02x", (unsigned char)alt.extra[b]);
            log += buf;
            if (b % 16 == 15) log += "\n";
            else log += " ";
          }
          log += "\n";
        }
      }
    }
  }

  xuDiagLog_ = log;
  ULOGI("UVC XU Diagnostics:\n%s", log.c_str());
}

bool UsbVideoStreamer::configureOutput(ANativeWindow* previewWindow) {
  if (!isStreamControlNegotiated_) {
    return false;
  }
  if (previewWindow_ == nullptr) {
    previewWindow_ = previewWindow;
  }
  uvc_error_t ret = uvc_stream_open_ctrl(deviceHandle_, &streamHandle_, &streamCtrl_);
  return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::start() {
  if (streamHandle_ == nullptr) {
    return false;
  }
  ULOGI(
      "streamCtrl fmtIdx=%u frameIdx=%u interval=%u maxFrameSize=%u maxPayload=%u",
      streamCtrl_.bFormatIndex,
      streamCtrl_.bFrameIndex,
      streamCtrl_.dwFrameInterval,
      streamCtrl_.dwMaxVideoFrameSize,
      streamCtrl_.dwMaxPayloadTransferSize);

  // Reset alt setting to 0 before starting to ensure the device
  // transitions from idle -> streaming, even if it was left in
  // streaming mode by a previous session that didn't clean up properly.
  int iface_num = streamHandle_->stream_if->bInterfaceNumber;
  ULOGI("Pre-start: resetting alt setting to 0 on interface %d", iface_num);
  int alt_ret = libusb_set_interface_alt_setting(deviceHandle_->usb_devh, iface_num, 0);
  ULOGI("Pre-start: alt setting reset result=%d", alt_ret);
  usleep(150000); // 150ms for device to settle

  // Clear any stale endpoint halt state
  uint8_t ep_addr = streamHandle_->stream_if->bEndpointAddress;
  ULOGI("Pre-start: clearing halt on endpoint 0x%02x", ep_addr);
  int halt_ret = libusb_clear_halt(deviceHandle_->usb_devh, ep_addr);
  ULOGI("Pre-start: clear halt result=%d", halt_ret);

  uvc_error_t ret = uvc_stream_start(streamHandle_, captureFrameCallback, this, 0);
  ULOGI("uvc_stream_start result=%d", ret);
  return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::stop() {
  if (streamHandle_ == nullptr) {
    return false;
  }
  uvc_stream_stop(streamHandle_);

  // Reset alt setting to 0 to properly stop isochronous data flow.
  // Without this, the device may remain in streaming mode and fail
  // to restart properly on the next connection.
  if (deviceHandle_ != nullptr && deviceHandle_->usb_devh != nullptr) {
    int iface_num = streamHandle_->stream_if->bInterfaceNumber;
    ULOGI("stop: resetting alt setting to 0 on interface %d", iface_num);
    libusb_set_interface_alt_setting(deviceHandle_->usb_devh, iface_num, 0);
  }

  return true;
}

bool UsbVideoStreamer::startRecording(const char* path, uvc::ContainerFormat container,
                                      int audioSampleRate, int audioChannels, int audioBitsPerSample) {
  if (recorder_ && recorder_->isRecording()) {
    ULOGW("Recording already active");
    return false;
  }
  recorder_ = std::make_unique<uvc::RawRecorder>();
  uvc::VideoSourceFormat srcFmt;
  switch (uvcFrameFormat_) {
    case UVC_FRAME_FORMAT_YUYV: srcFmt = uvc::VideoSourceFormat::YUYV; break;
    case UVC_FRAME_FORMAT_UYVY: srcFmt = uvc::VideoSourceFormat::UYVY; break;
    case UVC_FRAME_FORMAT_MJPEG: srcFmt = uvc::VideoSourceFormat::MJPEG; break;
    case UVC_FRAME_FORMAT_NV12: srcFmt = uvc::VideoSourceFormat::NV12; break;
    case UVC_FRAME_FORMAT_BGR: srcFmt = uvc::VideoSourceFormat::BGR24; break;
    case UVC_FRAME_FORMAT_RGB: srcFmt = uvc::VideoSourceFormat::RGB24; break;
    default: srcFmt = uvc::VideoSourceFormat::UNKNOWN; break;
  }
  int ret = recorder_->start(path, width_, height_, fps_, srcFmt, container,
                           audioSampleRate, audioChannels, audioBitsPerSample);
  return ret == 0;
}

void UsbVideoStreamer::stopRecording() {
  if (recorder_) {
    recorder_->stop();
    recorder_.reset();
  }
}

bool UsbVideoStreamer::isRecording() const {
  return recorder_ && recorder_->isRecording();
}

void UsbVideoStreamer::writeAudioToRecorder(const uint8_t* data, size_t len) {
  if (recorder_ && recorder_->isRecording()) {
    recorder_->writeAudio(data, len, -1);
  }
}

static std::string fourccFormatFromUvcFrameFormat(uvc_frame_format frameFormat) {
  switch (frameFormat) {
    case UVC_FRAME_FORMAT_YUYV:
      return "YUYV";
    case UVC_FRAME_FORMAT_UYVY:
      return "UYVY";
    case UVC_FRAME_FORMAT_MJPEG:
      return "MJPG";
    case UVC_FRAME_FORMAT_H264:
      return "H264";
    case UVC_FRAME_FORMAT_NV12:
      return "NV12";
    default:
      return "";
  }
  return "";
}

static bool isValidMjpegFrame(uvc_frame_t* frame) {
  // See https://en.wikipedia.org/wiki/JPEG_File_Interchange_Format
  if (frame->data_bytes < 6 || frame->data == nullptr) {
    ULOGE("Invalid MJPEG frame size %zu ptr %p", frame->data_bytes, frame->data);
    return false;
  }
  u_int8_t soi1 = *(u_int8_t*)frame->data;
  u_int8_t soi2 = *((u_int8_t*)frame->data + 1);
  // JPEG frame start of image (SOI) is 0xff 0xd8.
  if (soi1 != 0xff || soi2 != 0xd8) {
    ULOGE("Invalid MJPEG frame SOI. size: %zu SOI: %x%x", frame->data_bytes, soi1, soi2);
    return false;
  }
  return true;
}

std::string UsbVideoStreamer::statsSummaryString() const {
  return std::format(
      "{} {}x{} @{} fps [ctrl fmtIdx={} frameIdx={} interval={} maxFrame={}]",
      fourccFormatFromUvcFrameFormat(captureFrameFormat_),
      captureFrameWidth_,
      captureFrameHeight_,
      stats_.fps,
      streamCtrl_.bFormatIndex,
      streamCtrl_.bFrameIndex,
      streamCtrl_.dwFrameInterval,
      streamCtrl_.dwMaxVideoFrameSize);
}

UsbVideoStreamer::~UsbVideoStreamer() {
  if (streamHandle_ != nullptr) {
    ULOGI("Close stream handle");
    uvc_stream_close(streamHandle_);
    streamHandle_ = nullptr;
  }

  if (deviceHandle_ != nullptr) {
    ULOGI("Close device handle");
    uvc_close(deviceHandle_);
    deviceHandle_ = nullptr;
  }

  if (uvcContext_ != nullptr) {
    ULOGI("Exit UVC Context");
    uvc_exit(uvcContext_);
    uvcContext_ = nullptr;
  }

  ULOGI("UsbVideoStreamer destroyed");
}

bool UsbVideoStreamer::reconfigureStream(int32_t width, int32_t height, int32_t fps, uvc_frame_format format, ANativeWindow* previewWindow) {
  ULOGI("reconfigureStream called: %dx%d@%dfps format=%d previewWindow=%p (current=%p)",
        width, height, fps, format, previewWindow, previewWindow_);
  // Stop and close current stream if active
  if (streamHandle_ != nullptr) {
    ULOGI("Stopping current stream for reconfiguration");
    uvc_stream_stop(streamHandle_);

    // Reset alt setting to 0 to properly stop isochronous data flow.
    // Without this, the device continues sending data on the endpoint
    // which causes LIBUSB_TRANSFER_ERROR when new transfers are submitted.
    int iface_num = streamHandle_->stream_if->bInterfaceNumber;
    ULOGI("Resetting alt setting to 0 on interface %d", iface_num);
    int alt_ret = libusb_set_interface_alt_setting(deviceHandle_->usb_devh, iface_num, 0);
    ULOGI("libusb_set_interface_alt_setting(0) returned %d", alt_ret);

    uvc_stream_close(streamHandle_);
    streamHandle_ = nullptr;

    // Give the USB device time to settle after stream stop
    usleep(150000); // 150ms
    ULOGI("USB settle delay complete");
  }

  // Update format parameters
  width_ = width;
  height_ = height;
  fps_ = fps;
  uvcFrameFormat_ = format;

  // Re-negotiate stream control with new format
  uvc_error_t res = uvc_get_stream_ctrl_format_size(
      deviceHandle_, &streamCtrl_, format, width, height, fps);
  if (res != UVC_SUCCESS) {
    isStreamControlNegotiated_ = false;
    ULOGE("uvc_get_stream_ctrl_format_size for reconfigure failed %s", uvc_strerror(res));
    return false;
  }
  isStreamControlNegotiated_ = true;
  captureFrameWidth_ = width;
  captureFrameHeight_ = height;
  captureFrameFps_ = fps;
  captureFrameFormat_ = format;
  ULOGI("Reconfigured stream to %dx%d@%dfps format: %d", width, height, fps, format);

  // Update preview window
  previewWindow_ = previewWindow;

  // Open new stream handle
  res = uvc_stream_open_ctrl(deviceHandle_, &streamHandle_, &streamCtrl_);
  if (res != UVC_SUCCESS) {
    ULOGE("uvc_stream_open_ctrl for reconfigure failed %s", uvc_strerror(res));
    return false;
  }

  // Reset stats so captureFrameCallback re-initializes for new format
  stats_ = UsbVideoStreamerStats{};

  // Update ANativeWindow geometry for new resolution
  int geom_ret = ANativeWindow_setBuffersGeometry(previewWindow_, width, height, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
  ULOGI("ANativeWindow_setBuffersGeometry set to %dx%d result=%d", width, height, geom_ret);

  // ── Pre-start sequence (mirrors start()) ──
  // Force alt setting to 0 so the device transitions idle → streaming
  {
    int iface_num = streamHandle_->stream_if->bInterfaceNumber;
    ULOGI("Pre-start: resetting alt setting to 0 on interface %d", iface_num);
    int alt_ret = libusb_set_interface_alt_setting(deviceHandle_->usb_devh, iface_num, 0);
    ULOGI("Pre-start: alt setting reset result=%d", alt_ret);
  }
  usleep(200000); // 200ms settle — device must finish format switch

  // Clear any stale endpoint halt state
  uint8_t ep_addr = streamHandle_->stream_if->bEndpointAddress;
  ULOGI("Pre-start: clearing halt on endpoint 0x%02x", ep_addr);
  int halt_ret = libusb_clear_halt(deviceHandle_->usb_devh, ep_addr);
  ULOGI("Pre-start: libusb_clear_halt returned %d", halt_ret);

  // Start streaming
  res = uvc_stream_start(streamHandle_, captureFrameCallback, this, 0);
  ULOGI("uvc_stream_start for reconfigure result=%d", res);
  return res == UVC_SUCCESS;
}

/* This callback function runs once per frame. */
void UsbVideoStreamer::captureFrameCallback(uvc_frame_t* frame, void* user_data) {
  UsbVideoStreamer* self = (UsbVideoStreamer*)user_data;
  UsbVideoStreamerStats& stats = self->stats_;
  stats.frameCount++;
  int frameCount = stats.frameCount;  // local alias for readability
  if (frameCount <= 5 || frameCount % 60 == 0) {
    ULOGI("Frame #%d callback: format=%d %dx%d data=%zu step=%zu",
          frameCount, frame->frame_format, frame->width, frame->height,
          frame->data_bytes, frame->step);
  }
  size_t expectedSize;
  switch (frame->frame_format) {
    case UVC_FRAME_FORMAT_NV12:
      expectedSize = frame->width * frame->height + frame->width * frame->height / 2;
      if (frame->data_bytes != expectedSize) {
        ULOGE(
            "Invalid NV12 frame size %zu vs expected %zu for %dx%d, step %zu frame",
            frame->data_bytes,
            expectedSize,
            frame->width,
            frame->height,
            frame->step);
        return;
      }
      break;
    case UVC_FRAME_FORMAT_YUYV:
      expectedSize = frame->width * frame->height * 2;
      if (frame->data_bytes != expectedSize) {
        ULOGE(
            "Invalid YUYV frame size %zu vs expected %zu for %dx%d, step %zu frame",
            frame->data_bytes,
            expectedSize,
            frame->width,
            frame->height,
            frame->step);
        return;
      }
      break;
    case UVC_FRAME_FORMAT_MJPEG:
      if (!isValidMjpegFrame(frame)) {
        return;
      }
      break;
    case UVC_FRAME_FORMAT_BGR:
    case UVC_FRAME_FORMAT_RGB:
      expectedSize = frame->width * frame->height * 3;
      if (frame->data_bytes != expectedSize) {
        ULOGE(
            "Invalid BGR/RGB frame size %zu vs expected %zu for %dx%d, step %zu frame",
            frame->data_bytes,
            expectedSize,
            frame->width,
            frame->height,
            frame->step);
        return;
      }
      break;
    default:
      break;
  }

  ANativeWindow* preview_window = self->previewWindow_;
  bool first_call = stats.lastFpsUpdate.time_since_epoch().count() == 0;
  if (first_call) {
    prctl(PR_SET_NAME, "usb_video_capture");
    ULOGI("__ANDROID_MIN_SDK_VERSION__ %d", __ANDROID_MIN_SDK_VERSION__);
    ULOGI(
        "Capture frame format: %d data bytes: %zu step: %zd %dx%d",
        frame->frame_format,
        frame->data_bytes,
        frame->step,
        frame->width,
        frame->height);
    stats.captureRenderClock_ = high_resolution_clock::now();
  } else {
    stats.recordCapture();
  }

  if (preview_window == nullptr) {
    ULOGE("preview_window is null in captureFrameCallback");
    return;
  }

  ANativeWindow_Buffer buffer;
  auto status = ANativeWindow_lock(preview_window, &buffer, nullptr);
  if (status != 0) {
    ULOGE("ANativeWindow_lock failed with error %d", status);
    return;
  }

  if (first_call || (frameCount % 60 == 0 && frameCount <= 240)) {
    ULOGI(
        "Display buffer format: %d  stride: %d %dx%d (frameCount=%d, srcFormat=%d)",
        buffer.format,
        buffer.stride,
        buffer.width,
        buffer.height,
        frameCount,
        frame->frame_format);
  }

  if (frame->frame_format == UVC_FRAME_FORMAT_NV12) {
    int32_t hardware_buffer_format = buffer.format;
    if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
      uint8_t* src_y = (uint8_t*)frame->data;
      uint8_t* dest_rgb = (uint8_t*)buffer.bits;
      libyuv::NV21ToARGB(
          src_y,
          frame->step,
          (src_y + frame->width * frame->height),
          frame->step,
          dest_rgb,
          buffer.stride * 4,
          buffer.width,
          buffer.height);
    } else if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM) {
      uint8_t* src_y = (uint8_t*)frame->data;
      uint8_t* dest_rgb = (uint8_t*)buffer.bits;
      libyuv::NV21ToRGB24(
          src_y,
          frame->step,
          (src_y + frame->width * frame->height),
          frame->step,
          dest_rgb,
          buffer.stride * 3,
          buffer.width,
          buffer.height);
    } else {
      ULOGE("Unsupported hardware format for UVC_FRAME_FORMAT_NV12 %d", hardware_buffer_format);
    }
  } else if (frame->frame_format == UVC_FRAME_FORMAT_YUYV) {
    uint8_t* src_yuy2 = (uint8_t*)frame->data;
    uint8_t* dest_rgba = (uint8_t*)buffer.bits;
    libyuv::YUY2ToARGB(
        src_yuy2, frame->step, dest_rgba, 4 * buffer.stride, buffer.width, buffer.height);
    libyuv::ABGRToARGB(
        dest_rgba, 4 * buffer.stride, dest_rgba, 4 * buffer.stride, buffer.width, buffer.height);
  } else if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG) {
#if __ANDROID_MIN_SDK_VERSION__ >= 30
    AImageDecoder* decoder;
    int result = AImageDecoder_createFromBuffer(frame->data, frame->data_bytes, &decoder);
    if (result == ANDROID_IMAGE_DECODER_SUCCESS) {
      size_t stride = buffer.stride * 4;
      size_t size = buffer.height * stride;
      result = AImageDecoder_decodeImage(decoder, buffer.bits, stride, size);
      if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder);
        ULOGE(
            "MJPG decoding error %d frame size %zu %dx%d, step %zu, decoded image header: %d X %d stride %zu mime-type %s",
            result,
            frame->data_bytes,
            frame->width,
            frame->height,
            frame->step,
            AImageDecoderHeaderInfo_getWidth(info),
            AImageDecoderHeaderInfo_getHeight(info),
            stride,
            AImageDecoderHeaderInfo_getMimeType(info));
        memset(buffer.bits, 0, size);
      }
      AImageDecoder_delete(decoder);
    } else {
      ULOGE(
          "MJPG AImageDecoder_createFromBuffer error %d frame size %zu %dx%d, step %zu",
          result,
          frame->data_bytes,
          frame->width,
          frame->height,
          frame->step);
    }
#else
    std::unique_ptr<uvc_frame_t, decltype(&uvc_free_frame)> rgb_frame_ptr(
        uvc_allocate_frame(frame->width * frame->height * 3), &uvc_free_frame);
    uvc_frame_t* rgb_frame = rgb_frame_ptr.get();
    uvc_mjpeg2rgb(frame, rgb_frame);
    uint8_t* src_rgb = (uint8_t*)rgb_frame->data;
    uint8_t* dest_rgba = (uint8_t*)buffer.bits;
    int32_t hardware_buffer_format = buffer.format;
    if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
      libyuv::RGB24ToARGB(
          src_rgb, rgb_frame->step, dest_rgba, buffer.stride * 4, buffer.width, buffer.height);
    } else {
      ULOGE("Unsupported hardware_buffer_format  %d for MJPEG frame", hardware_buffer_format);
    }
#endif
  } else if (frame->frame_format == UVC_FRAME_FORMAT_BGR || frame->frame_format == UVC_FRAME_FORMAT_RGB) {
    uint8_t* src = (uint8_t*)frame->data;
    uint8_t* dest = (uint8_t*)buffer.bits;
    int32_t hardware_buffer_format = buffer.format;
    if (hardware_buffer_format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
      if (frame->frame_format == UVC_FRAME_FORMAT_BGR) {
        libyuv::RAWToARGB(
            src, frame->step, dest, buffer.stride * 4, buffer.width, buffer.height);
      } else {
        libyuv::RGB24ToARGB(
            src, frame->step, dest, buffer.stride * 4, buffer.width, buffer.height);
      }
    } else {
      ULOGE("Unsupported hardware_buffer_format %d for BGR/RGB frame", hardware_buffer_format);
    }
  } else {
    ULOGE("Unsupported  frame->frame_format %d", frame->frame_format);
  }
  ANativeWindow_unlockAndPost(preview_window);

  // Write raw frame to recorder if active (copy mode)
  if (self->recorder_ && self->recorder_->isRecording()) {
    int64_t pts = -1;
    self->recorder_->writeFrame(
        reinterpret_cast<const uint8_t*>(frame->data),
        frame->data_bytes,
        pts);
  }

  stats.recordRender();
  stats.recordFrame();
  stats.frames++;
  auto frame_count = stats.frames;
  auto now = steady_clock::now();
  if (first_call) {
    stats.lastFpsUpdate = now;
  }
  duration<float> diff = duration_cast<seconds>(now - stats.lastFpsUpdate);
  if (diff >= 10.0s) {
    auto fps = frame_count / diff.count();
    duration<double, seconds::period> captureDuration(stats.capture_);
    duration<double, seconds::period> renderDuration(stats.render_);
    auto capturePlusRender = captureDuration.count() + renderDuration.count();
    ULOGI(
        "Captured %dx%d %u frames in %.1f secs. fps: %.1f. Capture time: %.2f (%.0f%%) Render Time: %.2f (%.0f%%)",
        frame->width,
        frame->height,
        frame_count,
        diff.count(),
        fps,
        captureDuration.count(),
        captureDuration.count() * 100 / capturePlusRender,
        renderDuration.count(),
        renderDuration.count() * 100 / capturePlusRender);
    stats.lastFpsUpdate = now;
    stats.frames = 0;
    stats.capture_ = 0ns;
    stats.render_ = 0ns;
  }
}
