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

#include "UsbAudioStreamer.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <libusb/libusb.h>

#include <format>
#include <memory>
#include "RingBuffer.h"
#include "aaudio_type_conversion.h"

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbAudioStreamer", __VA_ARGS__)

#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbAudioStreamer", __VA_ARGS__)

UsbAudioStreamer::~UsbAudioStreamer() {
  if (audioStream_ != nullptr) {
    AAudioStream_close(audioStream_);
    audioStream_ = nullptr;
  }

  state_ = StreamerState::DESTROYING;

  if (deviceHandle_ && claimedControlInterface_ != -1) {
    auto status = libusb_release_interface(deviceHandle_, claimedControlInterface_);
    if (status == LIBUSB_SUCCESS) {
      ULOGI("Released claimed audio control interface %d", claimedControlInterface_);
    } else {
      ULOGW("Could not release audio control interface %d: %s", claimedControlInterface_, libusb_error_name(status));
    }
  }

  if (deviceHandle_ && claimedInterface_ != -1) {
    auto status = libusb_release_interface(deviceHandle_, claimedInterface_);
    if (status == LIBUSB_SUCCESS) {
      ULOGI("Released claimed audio streaming interface");
    } else {
      ULOGW("Could not release claimed audio streaming interface %s", libusb_error_name(status));
    }
  }

  if (detachedInterface_ != -1) {
    auto status = libusb_attach_kernel_driver(deviceHandle_, detachedInterface_);
    if (status == LIBUSB_SUCCESS) {
      ULOGI("Attached audio interface to kernel driver");
    } else {
      ULOGW("Could not attach audio interface to kernel driver %s", libusb_error_name(status));
    }
  }

  if (deviceHandle_ != nullptr) {
    ULOGI("Free device");
    libusb_close(deviceHandle_);
    deviceHandle_ = nullptr;
  }

  // this will call libusb_free_transfer in destructor
  transfers_.clear();

  if (config_ != nullptr) {
    ULOGI("Free config");
    libusb_free_config_descriptor(config_);
  }

  if (context_ != nullptr) {
    ULOGI("Exit context");
    libusb_exit(context_);
  }

  ringBuffer_ = nullptr;

  ULOGI("UsbAudioStreamer destroyed");
  state_ = StreamerState::DESTROYED;
}

bool UsbAudioStreamer::resolveAudioInterface() {
  if (config_ == nullptr) {
    return false;
  }

  ULOGI("resolveAudioInterface: looking for iface=%d alt=%d (ch=%d)",
        desiredInterfaceNumber_, desiredAltSetting_, channelCount_);

  char diagBuf[512];
  ctrlDiagLog_ = "";

  // ================================================================
  // Phase 1: Claim Audio Control interface FIRST (Windows driver order)
  // ================================================================
  int acIfaceNum = -1;
  for (auto i = 0; i < config_->bNumInterfaces; ++i) {
    const auto iface = &config_->interface[i];
    for (auto j = 0; j < iface->num_altsetting; ++j) {
      const auto desc = &iface->altsetting[j];
      if (desc->bInterfaceClass == LIBUSB_CLASS_AUDIO &&
          desc->bInterfaceSubClass == kInterfaceSubClassControl) {
        acIfaceNum = desc->bInterfaceNumber;

        // Dump AC class-specific descriptors for diagnostics
        for (int e = 0; e < desc->extra_length; ) {
          uint8_t bLen = desc->extra[e];
          if (bLen < 2 || e + bLen > desc->extra_length) break;
          uint8_t bType = desc->extra[e + 1];
          if (bType == 0x24 && bLen >= 3) { // CS_INTERFACE
            uint8_t subtype = desc->extra[e + 2];
            if (subtype == 0x02 && bLen >= 12) { // INPUT_TERMINAL
              uint8_t termId = desc->extra[e + 3];
              uint16_t termType = desc->extra[e + 4] | (desc->extra[e + 5] << 8);
              uint8_t nrCh = desc->extra[e + 7];
              uint16_t chConfig = desc->extra[e + 8] | (desc->extra[e + 9] << 8);
              ULOGI("AC: InputTerminal ID=0x%02x type=0x%04x nrCh=%d chConfig=0x%04x",
                    termId, termType, nrCh, chConfig);
              snprintf(diagBuf, sizeof(diagBuf), "IT(0x%02x,type=0x%04x,%dch,cfg=0x%04x) ",
                       termId, termType, nrCh, chConfig);
              ctrlDiagLog_ += diagBuf;
            } else if (subtype == 0x06 && bLen >= 7) { // FEATURE_UNIT
              uint8_t unitId = desc->extra[e + 3];
              uint8_t srcId = desc->extra[e + 4];
              uint8_t ctrlSize = desc->extra[e + 5];
              uint8_t masterCtrl = (bLen > 6) ? desc->extra[e + 6] : 0;
              ULOGI("AC: FeatureUnit ID=0x%02x src=0x%02x ctrlSize=%d master=0x%02x",
                    unitId, srcId, ctrlSize, masterCtrl);
            } else if (subtype == 0x03 && bLen >= 9) { // OUTPUT_TERMINAL
              uint8_t termId = desc->extra[e + 3];
              uint16_t termType = desc->extra[e + 4] | (desc->extra[e + 5] << 8);
              uint8_t srcId = desc->extra[e + 7];
              ULOGI("AC: OutputTerminal ID=0x%02x type=0x%04x src=0x%02x",
                    termId, termType, srcId);
            }
          }
          e += bLen;
        }
        break;
      }
    }
    if (acIfaceNum >= 0) break;
  }

  if (acIfaceNum >= 0) {
    if (libusb_kernel_driver_active(deviceHandle_, acIfaceNum) == 1) {
      libusb_detach_kernel_driver(deviceHandle_, acIfaceNum);
    }
    int rc = libusb_claim_interface(deviceHandle_, acIfaceNum);
    snprintf(diagBuf, sizeof(diagBuf), "ClaimAC(iface%d)=%s ", acIfaceNum,
             rc == 0 ? "OK" : libusb_error_name(rc));
    ctrlDiagLog_ += diagBuf;
    if (rc == 0) claimedControlInterface_ = acIfaceNum;
    ULOGI("Phase1: Claimed Audio Control iface %d: %s", acIfaceNum,
          rc == 0 ? "OK" : libusb_error_name(rc));
  } else {
    ctrlDiagLog_ += "NoACiface ";
    ULOGW("Phase1: No Audio Control interface found");
  }

  // ================================================================
  // Phase 2: Configure Feature Unit on AC interface (unmute)
  // ================================================================
  if (claimedControlInterface_ >= 0) {
    uint8_t muteOff = 0;
    int rc = libusb_control_transfer(deviceHandle_,
        0x21, 0x01, 0x0100, (0x0B << 8) | claimedControlInterface_,
        &muteOff, 1, 1000);
    snprintf(diagBuf, sizeof(diagBuf), "Unmute=%s ", rc >= 0 ? "OK" : libusb_error_name(rc));
    ctrlDiagLog_ += diagBuf;

    uint8_t muteVal = 0xFF;
    libusb_control_transfer(deviceHandle_,
        0xA1, 0x81, 0x0100, (0x0B << 8) | claimedControlInterface_,
        &muteVal, 1, 1000);
    snprintf(diagBuf, sizeof(diagBuf), "Mute=%d ", muteVal);
    ctrlDiagLog_ += diagBuf;
    ULOGI("Phase2: Feature Unit unmute: rc=%d, readback=%d", rc, muteVal);
  }

  // ================================================================
  // Phase 3: Find & claim Audio Streaming interface, set alt setting
  // ================================================================
  // Log all available audio streaming alt settings for debugging
  for (auto i = 0; i < config_->bNumInterfaces; ++i) {
    const auto interface = &config_->interface[i];
    for (auto j = 0; j < interface->num_altsetting; ++j) {
      const auto desc = &interface->altsetting[j];
      if (desc->bInterfaceClass == LIBUSB_CLASS_AUDIO &&
          desc->bInterfaceSubClass == kInterfaceSubClassStreaming) {
        ULOGI("  AS iface %u alt %u endpoints=%u",
              desc->bInterfaceNumber, desc->bAlternateSetting, desc->bNumEndpoints);
      }
    }
  }

  for (auto i = 0; i < config_->bNumInterfaces; ++i) {
    const auto interface = &config_->interface[i];
    for (auto j = 0; j < interface->num_altsetting; ++j) {
      const auto interfaceDescriptor = &interface->altsetting[j];
      if (interfaceDescriptor->bInterfaceClass != LIBUSB_CLASS_AUDIO ||
          interfaceDescriptor->bInterfaceSubClass != kInterfaceSubClassStreaming ||
          interfaceDescriptor->bNumEndpoints == 0) {
        continue;
      }

      if (desiredInterfaceNumber_ >= 0 && desiredAltSetting_ >= 0) {
        if ((int)interfaceDescriptor->bInterfaceNumber != desiredInterfaceNumber_ ||
            (int)interfaceDescriptor->bAlternateSetting != desiredAltSetting_) {
          continue;
        }
      }

      for (auto k = 0; k < interfaceDescriptor->bNumEndpoints; k++) {
        auto const endpoint = &interfaceDescriptor->endpoint[k];
        if ((endpoint->bEndpointAddress & LIBUSB_ENDPOINT_IN) == 0) continue;

        auto interface_number = interfaceDescriptor->bInterfaceNumber;
        endpointAddress_ = endpoint->bEndpointAddress;
        maxPacketSize_ = endpoint->wMaxPacketSize;
        ULOGI("Phase3: Selected iface %u alt %u ep 0x%02x maxPkt %d",
              interface_number, interfaceDescriptor->bAlternateSetting,
              endpointAddress_, maxPacketSize_);

        if (libusb_kernel_driver_active(deviceHandle_, interface_number) == 1) {
          auto s = libusb_detach_kernel_driver(deviceHandle_, interface_number);
          if (s != LIBUSB_SUCCESS) {
            ULOGE("detach kernel driver iface %d: %s", interface_number, libusb_error_name(s));
            return false;
          }
          detachedInterface_ = interface_number;
        }

        // Windows driver: first claim with alt 0, then switch to desired alt
        auto rc = libusb_claim_interface(deviceHandle_, interface_number);
        if (rc != LIBUSB_SUCCESS) {
          ULOGE("claim iface %d: %s", interface_number, libusb_error_name(rc));
          return false;
        }
        claimedInterface_ = i;

        // Set alt 0 first (zero-bandwidth), then switch to desired alt
        libusb_set_interface_alt_setting(deviceHandle_, interface_number, 0);

        rc = libusb_set_interface_alt_setting(deviceHandle_, interface_number,
                interfaceDescriptor->bAlternateSetting);
        if (rc != LIBUSB_SUCCESS) {
          ULOGE("set alt %d on iface %d: %s", interfaceDescriptor->bAlternateSetting,
                interface_number, libusb_error_name(rc));
          return false;
        }
        snprintf(diagBuf, sizeof(diagBuf), "AS(iface%d,alt%d)=OK ",
                 interface_number, interfaceDescriptor->bAlternateSetting);
        ctrlDiagLog_ += diagBuf;
        ULOGI("Phase3: Claimed AS iface %d alt %d (ch=%d)",
              interface_number, interfaceDescriptor->bAlternateSetting, channelCount_);

        // ================================================================
        // Phase 4: SET_CUR Sampling Frequency to endpoint
        // ================================================================
        {
          uint8_t freqData[3];
          freqData[0] = (uint8_t)(samplingFrequency_ & 0xFF);
          freqData[1] = (uint8_t)((samplingFrequency_ >> 8) & 0xFF);
          freqData[2] = (uint8_t)((samplingFrequency_ >> 16) & 0xFF);
          int sr = libusb_control_transfer(deviceHandle_,
              0x22, 0x01, 0x0100, endpointAddress_, freqData, 3, 1000);
          snprintf(diagBuf, sizeof(diagBuf), "SetFreq(%d)=%s ",
                   samplingFrequency_, sr >= 0 ? "OK" : libusb_error_name(sr));
          ctrlDiagLog_ += diagBuf;

          uint8_t curFreq[3] = {0};
          int gr = libusb_control_transfer(deviceHandle_,
              0xA2, 0x81, 0x0100, endpointAddress_, curFreq, 3, 1000);
          uint32_t rFreq = curFreq[0] | (curFreq[1] << 8) | (curFreq[2] << 16);
          snprintf(diagBuf, sizeof(diagBuf), "GetFreq=%s(%dHz) ",
                   gr >= 0 ? "OK" : libusb_error_name(gr), rFreq);
          ctrlDiagLog_ += diagBuf;
          ULOGI("Phase4: SetFreq %dHz → GetFreq %dHz", samplingFrequency_, rFreq);
        }

        ULOGI("All phases complete. Diag: %s", ctrlDiagLog_.c_str());
        return true;
      }
    }
  }
  ULOGE("resolveAudioInterface: no matching interface found!");
  return false;
}

UsbAudioStreamer::UsbAudioStreamer(
        intptr_t deviceFD,
        uint32_t jAudioFormat,
        uint32_t samplingFrequency,
        uint8_t subFrameSize,
        uint8_t channelCount,
        uint32_t jAudioPerfMode,
        uint32_t framesPerBurst,
        int desiredInterfaceNumber,
        int desiredAltSetting)
        : jAudioFormat_(jAudioFormat),
          samplingFrequency_(samplingFrequency),
          subFrameSize_(subFrameSize),
          channelCount_(channelCount),
          framesPerBurst_(framesPerBurst),
          desiredInterfaceNumber_(desiredInterfaceNumber),
          desiredAltSetting_(desiredAltSetting) {
  ULOGI(
          "UsbAudioStreamer::init samplingFrequency_: %d channelCount_: %d framesPerBurst_ %d",
          samplingFrequency_,
          channelCount_,
          framesPerBurst_);
  int errcode = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb setting no discovery option failed %s", libusb_error_name(errcode));
  }

  ULOGI("Initializing UsbContext");
  errcode = libusb_init(&context_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_init failed %s", libusb_error_name(errcode));
  } else {
    ULOGD("libusb initialized");
  }

  errcode = libusb_set_option(context_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_ERROR);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb setting loglevel option failed %s", libusb_error_name(errcode));
    return;
  }

  errcode = libusb_wrap_sys_device(context_, deviceFD, &deviceHandle_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_wrap_sys_device failed %s", libusb_error_name(errcode));
    return;
  }

  libusb_device* device = libusb_get_device(deviceHandle_);
  ULOGD("Got device %p with usb speed %d", device, libusb_get_device_speed(device));
  errcode = libusb_get_active_config_descriptor(device, &config_);
  if (errcode != LIBUSB_SUCCESS) {
    ULOGE("libusb_get_active_config_descriptor failed %s", libusb_error_name(errcode));
    return;
  }

  aaudio_result_t result = AAudio_createStreamBuilder(&audioStreamBuilder_);
  ULOGD("AAudio_createStreamBuilder result %d.", result);
  if (result == AAUDIO_OK && audioStreamBuilder_ != nullptr) {
    AAudioStreamBuilder_setDirection(audioStreamBuilder_, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(audioStreamBuilder_, convertFormat(jAudioFormat_));
    AAudioStreamBuilder_setSampleRate(audioStreamBuilder_, samplingFrequency);
    AAudioStreamBuilder_setChannelCount(audioStreamBuilder_, channelCount);
    AAudioStreamBuilder_setPerformanceMode(audioStreamBuilder_, convertPerfMode(jAudioPerfMode));
    AAudioStreamBuilder_setDataCallback(audioStreamBuilder_, audioPlaybackCallback, this);
    result = AAudioStreamBuilder_openStream(audioStreamBuilder_, &audioStream_);
    ULOGD("AAudioStreamBuilder_openStream result %d.", result);
    framesPerBurst_ = AAudioStream_getFramesPerBurst(audioStream_);
    bufferCapacityInFrames_ = AAudioStream_getBufferCapacityInFrames(audioStream_);
    ULOGD(
            "AAudioStream params: framesPerBurst %d bufferSizeInFrames %d bufferCapacityInFrames = %d",
            AAudioStream_getFramesPerBurst(audioStream_),
            AAudioStream_getBufferSizeInFrames(audioStream_),
            AAudioStream_getBufferCapacityInFrames(audioStream_));
  } else {
    state_ = StreamerState::ERROR;
    return;
  }

  if (resolveAudioInterface()) {
    ULOGE("Resolved audio interface");
  } else {
    state_ = StreamerState::ERROR;
    ULOGE("Could not resolve audio interface");
    return;
  }
  allocateTransferRequests();

  state_ = StreamerState::READY_TO_START;
}

bool UsbAudioStreamer::start() {
  ULOGI("UsbAudioStreamer start called");
  if (state_ != StreamerState::READY_TO_START) {
    ULOGE("Streamer state must by ready to start");
    return false;
  }
  state_ = StreamerState::STARTING;
  streamerStats_.t0_10_s = {};
  streamerStats_.total_bytes = 0;
  streamerStats_.player_cb_counter = 0;
  streamerStats_.usb_cb_counter = 0;
  streamerStats_.event_loops = 0;
  stopUsbAudioCapture_ = 0;

  if(!submitTransferRequests()) {
    ULOGE("Submit transfer requests failed");
    return false;
  }

  if (!startAudioPlayer()) {
    state_ = StreamerState::ERROR;
    ULOGE("start audio player error");
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STARTING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  int64_t timeoutNanos = 500LL * 1000000LL;
  auto result = AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, timeoutNanos);
  ULOGD(
          "AAudioStream start: result %d nextState %d started(ref) = %d",
          result,
          nextState,
          AAUDIO_STREAM_STATE_STARTED);
  if (result != AAUDIO_OK) {
    state_ = StreamerState::ERROR;
    ULOGE("start audio player error");
    return false;
  }
  state_ = StreamerState::STARTED;
  return true;
}

bool UsbAudioStreamer::ensureTransferRequests() {
  if (!transfers_.empty()) {
    return false;
  }
  return this->submitTransferRequests();
}


bool UsbAudioStreamer::submitTransferRequests() {
  for (const auto& transferData: transfers_) {
    auto submit_transfer_status = libusb_submit_transfer(transferData->transfer);
    transferData->isSubmitted = submit_transfer_status == LIBUSB_SUCCESS;
  }

  int32_t submittedTransfers = std::count_if(
      transfers_.begin(),
      transfers_.end(),
      [](const std::unique_ptr<TransferUserData>& transferUserData) {
        return transferUserData->isSubmitted;
      });
  ULOGE("submitTransferRequests2 %d", submittedTransfers);
  if (submittedTransfers == 0) {
    state_ = StreamerState::ERROR;
    return false;
  } else {
    return true;
  }
}

void UsbAudioStreamer::allocateTransferRequests() {
  framesPerBurst_ = AAudioStream_getFramesPerBurst(audioStream_);
  bufferCapacityInFrames_ = AAudioStream_getBufferCapacityInFrames(audioStream_);
  ULOGD(
          "AAudioStream params: framesPerBurst %d bufferSizeInFrames %d bufferCapacityInFrames = %d",
          framesPerBurst_,
          AAudioStream_getBufferSizeInFrames(audioStream_),
          bufferCapacityInFrames_);
  auto bytes_per_burst = framesPerBurst_ * subFrameSize_ * channelCount_;
  auto computed_num_packets = (bytes_per_burst + maxPacketSize_ - 1) / maxPacketSize_;
  auto num_packets = std::max(2, computed_num_packets);
  auto buffer_size = maxPacketSize_ * num_packets;
  auto computed_num_transfers = (bufferCapacityInFrames_ + framesPerBurst_ - 1) / framesPerBurst_;
  int32_t num_transfers = std::max(2, computed_num_transfers);
  size_t ring_buffer_capacity = buffer_size * num_transfers / subFrameSize_;
  ULOGI(
          "ISO transfer params. maxPacketSize: %d num packets: %d buffer size: %d num transfers: %d",
          maxPacketSize_,
          num_packets,
          buffer_size,
          num_transfers);
  ULOGI(
          "Audio out params. framesPerBurst: %d bufferCapacityInFrames: %d, ring buffer capacity: %zu",
          framesPerBurst_,
          bufferCapacityInFrames_,
          ring_buffer_capacity);

  if (ringBuffer_->capacity() != ring_buffer_capacity) {
    ringBuffer_ = std::make_unique<RingBufferPcm>(ring_buffer_capacity);
  }

  for (auto i = 0; i < num_transfers; i++) {
    libusb_transfer* transfer = libusb_alloc_transfer(num_packets);
    if (transfer == nullptr) {
      ULOGD("libusb_alloc_transfer index %d failed.", i);
      continue;
    }
    ULOGD(
            "libusb_transfer initial status is %d. %p maxPacketSize_: %d",
            transfer->status,
            this,
            maxPacketSize_);
    transfers_.emplace_back(std::make_unique<TransferUserData>(transfer, this, false));
    TransferUserData* transferUserData = transfers_.back().get();
    libusb_fill_iso_transfer(
            transfer,
            deviceHandle_,
            (unsigned char)endpointAddress_,
            (unsigned char*)malloc(buffer_size),
            buffer_size,
            num_packets,
            transferCallback,
            transferUserData,
            kIsochronousTransferTimeoutMillis);
    transfer->flags = LIBUSB_TRANSFER_SHORT_NOT_OK | LIBUSB_TRANSFER_FREE_BUFFER;
    libusb_set_iso_packet_lengths(transfer, maxPacketSize_);
  }
}

bool UsbAudioStreamer::stop() {
  ULOGI("UsbAudioStreamer stop called");
  state_ = StreamerState::STOPPING;
  uint8_t tries{0};
  while (hasActiveTransfers() && tries++ < 5) {
    std::unique_lock lk(mutex_);
    stateChange_.wait_for(lk, 100ms);
  }
  stopUsbAudioCapture_ = 1;
  if (hasActiveTransfers() || !stopAudioPlayer()) {
    ULOGE("UsbAudioStreamer stop failed. Active Transfers %d", hasActiveTransfers());
    state_ = StreamerState::ERROR;
    return false;
  } else {
    state_ = StreamerState::READY_TO_START;
    return true;
  }
}

uint32_t UsbAudioStreamer::samplesFromByteCount(uint32_t byteCount) const {
  return byteCount / channelCount_ / subFrameSize_;
}

std::string UsbAudioStreamer::statsSummaryString() const {
  std::string audioFormatStr = "";
  if (jAudioFormat_ == 2) { // AudioFormat.ENCODING_PCM_16BIT
    audioFormatStr = "PCM16";
  } else if (jAudioFormat_ == 3) {
    audioFormatStr = "PCM8";
  } else if (jAudioFormat_ == 4) {
    audioFormatStr = "PCM Float";
  }
  // Include per-channel audio levels in stats for FileLogger diagnostics
  char levelBuf[256] = {};
  int pos = 0;
  for (int ch = 0; ch < channelCount_ && ch < MAX_AUDIO_CHANNELS && pos < 240; ch++) {
    pos += snprintf(levelBuf + pos, sizeof(levelBuf) - pos, "ch%d=%.4f ", ch, audioLevels_[ch]);
  }
  return std::format(
          "{} {}Ch. {} [iface={} alt={} ep=0x{:02x} maxPkt={}] levels: {}",
          audioFormatStr, channelCount_, streamerStats_.samplingFrequency,
          desiredInterfaceNumber_, desiredAltSetting_, endpointAddress_, maxPacketSize_,
          levelBuf);
}

aaudio_data_callback_result_t UsbAudioStreamer::audioPlaybackCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames) {
  UsbAudioStreamer* streamer = reinterpret_cast<UsbAudioStreamer*>(userData);
  auto sizeToRead = streamer->channelCount_ * numFrames;
  auto bytesToRead = streamer->bytesInAudioFrames(numFrames);

  streamer->streamerStats_.event_loops++;
  libusb_handle_events_timeout_completed(
          streamer->context(),
          &streamer->libusbEventsTimeout_,
          const_cast<int*>(&streamer->stopUsbAudioCapture_));
  streamer->streamerStats_.player_cb_counter++;

  auto available = streamer->ringBuffer_->size();

  // Mute speaker for initial period after connection to suppress startup noise
  bool initialMute = streamer->initialMuteMs_ > 0 &&
      (steady_clock::now() - streamer->playbackStartTime_) < milliseconds(streamer->initialMuteMs_);

  if (available < sizeToRead) {
    memset(audioData, 0, bytesToRead);
  } else {
    auto movedData = streamer->ringBuffer_->read((uint16_t*)audioData, sizeToRead);
    if (movedData != sizeToRead && streamer->state_ == StreamerState::STARTED) {
      ULOGD(
              "ringBuffer read error %zu sizeToRead %d read data = %d",
              available,
              sizeToRead,
              movedData);
    }
  }

  if (initialMute) {
    memset(audioData, 0, bytesToRead);
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool UsbAudioStreamer::startAudioPlayer() {
  playbackStartTime_ = steady_clock::now();
  if (AAudioStream_requestStart(audioStream_) != AAUDIO_OK) {
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STARTING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  int64_t timeoutNanos = 500LL * 1000000LL;
  AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, timeoutNanos);
  return nextState == AAUDIO_STREAM_STATE_STARTED;
}

bool UsbAudioStreamer::stopAudioPlayer() {
  if (AAudioStream_requestStop(audioStream_) != AAUDIO_OK) {
    return false;
  }
  aaudio_stream_state_t inputState = AAUDIO_STREAM_STATE_STOPPING;
  aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
  AAudioStream_waitForStateChange(audioStream_, inputState, &nextState, 500000000);
  return nextState == AAUDIO_STREAM_STATE_STOPPED;
}

void UsbAudioStreamer::setAudioRecordCallback(AudioRecordCallback cb) {
  std::lock_guard<std::mutex> lock(recordCallbackMutex_);
  audioRecordCallback_ = std::move(cb);
}

void UsbAudioStreamer::transferCallback(libusb_transfer* transfer) {
  if (transfer == nullptr) {
    ULOGE("transferCallback transfer is null.");
    return;
  }
  TransferUserData* transferUserData = reinterpret_cast<TransferUserData*>(transfer->user_data);

  if (transferUserData == nullptr) {
    ULOGE("transferUserData is null.");
    return;
  }
  transferUserData->isSubmitted = false;
  if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
    ULOGI("LIBUSB_TRANSFER_NO_DEVICE");
    return;
  }

  UsbAudioStreamer* streamer = transferUserData->streamer;
  const StreamerState state = streamer->state_;
  if (state == StreamerState::STOPPING) {
    if (streamer->hasActiveTransfers()) {
      ULOGE("Streamer has active transfers");
    } else {
      ULOGE("Streamer has no active transfers");
      std::unique_lock lk(streamer->mutex_);
      streamer->stateChange_.notify_one();
    }
    return;
  }
  if (state == StreamerState::DESTROYING || state == StreamerState::DESTROYED) {
    ULOGE("Streamer is shutting down");
    return;
  }

  int len = 0;
  for (auto i = 0; i < transfer->num_iso_packets; i++) {
    struct libusb_iso_packet_descriptor* pack = &transfer->iso_packet_desc[i];
    if (pack->status != LIBUSB_TRANSFER_COMPLETED) {
      const time_point<steady_clock> now = steady_clock::now();
      if (now - streamer->callbackErrorLoggedAt_ > 60s) {
        ULOGE("Error (status %d: %s)", pack->status, libusb_error_name(pack->status));
        streamer->callbackErrorLoggedAt_ = now;
      }
      continue;
    }
    const uint8_t* data = libusb_get_iso_packet_buffer_simple(transfer, i);

    auto sampleCount = pack->actual_length / streamer->subFrameSize_;
    // Per-channel level accumulation (up to MAX_AUDIO_CHANNELS)
    int chCount = streamer->channelCount_;
    if (chCount < 1) chCount = 2;
    if (chCount > UsbAudioStreamer::MAX_AUDIO_CHANNELS) chCount = UsbAudioStreamer::MAX_AUDIO_CHANNELS;
    double chSum[UsbAudioStreamer::MAX_AUDIO_CHANNELS] = {};
    int    chCnt[UsbAudioStreamer::MAX_AUDIO_CHANNELS] = {};

    if (streamer->subFrameSize_ == 3) {
      // Convert 24-bit PCM to 16-bit PCM (take upper 16 bits)
      std::vector<uint16_t> pcm16;
      pcm16.reserve(sampleCount);
      for (auto s = 0; s < sampleCount; s++) {
        const uint8_t* sample = data + s * streamer->subFrameSize_;
        uint16_t val = (sample[2] << 8) | sample[1];
        pcm16.push_back(val);
        int16_t signedVal = (int16_t)val;
        int ch = s % chCount;
        chSum[ch] += (double)signedVal * signedVal;
        chCnt[ch]++;
      }
      auto result = streamer->ringBuffer_->write(pcm16.data(), sampleCount);
      if (result != sampleCount) {
        ULOGE("Write error result = %d to write = %d", result, sampleCount);
      }
    } else {
      auto dataSize = pack->actual_length / 2;
      const uint16_t* samples = reinterpret_cast<const uint16_t*>(data);
      for (auto s = 0; s < dataSize; s++) {
        int16_t signedVal = (int16_t)samples[s];
        int ch = s % chCount;
        chSum[ch] += (double)signedVal * signedVal;
        chCnt[ch]++;
      }
      auto result = streamer->ringBuffer_->write((uint16_t*)data, dataSize);
      if (result != dataSize) {
        ULOGE("Write error result = %d to write = %d", result, pack->actual_length);
      }
    }
    // Update per-channel levels
    for (int ch = 0; ch < chCount; ch++) {
      if (chCnt[ch] > 0) {
        streamer->audioLevels_[ch] = std::min(1.0f, (float)(sqrt(chSum[ch] / chCnt[ch]) / 32768.0));
      }
    }

    // Diagnostic: log per-channel levels for first 20 packets and every 500th
    static uint32_t diagPktCount = 0;
    diagPktCount++;
    auto cbCount = diagPktCount;
    if (cbCount <= 20 || cbCount % 500 == 0) {
      char levelBuf[256];
      int pos = 0;
      for (int ch = 0; ch < chCount && pos < 240; ch++) {
        pos += snprintf(levelBuf + pos, sizeof(levelBuf) - pos, "ch%d=%.4f ", ch, streamer->audioLevels_[ch]);
      }
      ULOGI("pkt#%d len=%d samples=%d chCount=%d | %s",
            (int)cbCount, pack->actual_length, (int)(pack->actual_length / streamer->subFrameSize_),
            chCount, levelBuf);
    }

    // Capture hex dump of first packet with data for FileLogger diagnostics
    if (cbCount == 1 && pack->actual_length > 0 && streamer->rawPktHexDump_.empty()) {
      int dumpLen = std::min((int)pack->actual_length, 72); // first 72 bytes = 6 frames of 6ch 16bit
      char hexBuf[256] = {};
      int hp = 0;
      for (int b = 0; b < dumpLen && hp < 250; b++) {
        if (b > 0 && b % (streamer->subFrameSize_ * chCount) == 0)
          hp += snprintf(hexBuf + hp, sizeof(hexBuf) - hp, "|"); // frame separator
        hp += snprintf(hexBuf + hp, sizeof(hexBuf) - hp, "%02x", data[b]);
      }
      streamer->rawPktHexDump_ = hexBuf;
    }

    // Forward raw PCM data to recording callback if active
    if (pack->actual_length > 0) {
      std::lock_guard<std::mutex> lock(streamer->recordCallbackMutex_);
      if (streamer->audioRecordCallback_) {
        static int recordCbCount = 0;
        recordCbCount++;
        if (recordCbCount <= 20 || recordCbCount % 1000 == 0) {
          ULOGI("Audio record callback: count=%d len=%d", recordCbCount, pack->actual_length);
        }
        streamer->audioRecordCallback_(data, pack->actual_length);
      }
    }

    len += pack->actual_length;
  }

  /* update stats */
  UsbAudioStreamerStats& stats = streamer->streamerStats_;

  if (len > 0) {
    stats.recordSamples(streamer->samplesFromByteCount(len));
  }

  const time_point<steady_clock> now = steady_clock::now();
  if (stats.t0_10_s.time_since_epoch().count() == 0) {
    stats.t0_10_s = now;
    stats.total_bytes = 0;
    stats.player_cb_counter = 0;
    stats.usb_cb_counter = 0;
  }
  stats.total_bytes += len;
  stats.usb_cb_counter++;

  duration<float> diff = duration_cast<seconds>(now - stats.t0_10_s);
  if (diff >= 10.0s) {
    ULOGI(
            "Audio callbacks %hu usb callbacks %hu in %hu event loops. Transferred  %d in %.1f secs, speed %.1f bps",
            stats.player_cb_counter,
            stats.usb_cb_counter,
            stats.event_loops,
            stats.total_bytes,
            diff.count(),
            stats.total_bytes / diff.count());
    stats.t0_10_s = now;
    stats.total_bytes = 0;
    stats.player_cb_counter = 0;
    stats.usb_cb_counter = 0;
    stats.event_loops = 0;
  }

  int maxExpectedLen = streamer->maxPacketSize_ * transfer->num_iso_packets;

  if (len > maxExpectedLen) {
    ULOGE("Error: incoming transfer data %d > maxExpectedLen %d (maxPacketSize=%d * num_iso_packets=%d)",
            len, maxExpectedLen, streamer->maxPacketSize_, transfer->num_iso_packets);
    // Clamp len to maxExpectedLen and continue processing
    len = maxExpectedLen;
  }

  // ALWAYS resubmit the transfer to keep audio streaming
  auto status = libusb_submit_transfer(transfer);
  if (status == LIBUSB_SUCCESS) {
    transferUserData->isSubmitted = true;
  } else if (status == LIBUSB_ERROR_NO_DEVICE) {
    ULOGE("LOST DEVICE libusb_submit_transfer: %s.", libusb_error_name(status));
  } else {
    ULOGE("libusb_submit_transfer failed: %s. Audio streaming may stop!", libusb_error_name(status));
    // Mark as not submitted so cleanup knows not to cancel it
    transferUserData->isSubmitted = false;
  }
}

bool UsbAudioStreamer::isPlaying() const {
  return state_ == StreamerState::STARTED;
}
