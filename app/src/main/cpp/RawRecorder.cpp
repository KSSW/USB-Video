/*
 * RawRecorder.cpp
 *
 * FFmpeg-based raw bitstream recorder implementation.
 */

#include "RawRecorder.h"

#include <android/log.h>
#include <algorithm>
#include <climits>
#include <cstring>
#include <ctime>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/dict.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <jni.h>
#include <pthread.h>
}

using namespace uvc;

#define LOG_TAG "RawRecorder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uvc {

RawRecorder::RawRecorder() {
    pthread_mutex_init(&mutex, nullptr);
    pthread_mutex_init(&queueMutex, nullptr);
    pthread_cond_init(&queueCond, nullptr);
    errBuf[0] = '\0';
}

RawRecorder::~RawRecorder() {
    stop();
    pthread_mutex_destroy(&mutex);
    pthread_mutex_destroy(&queueMutex);
    pthread_cond_destroy(&queueCond);
}

bool RawRecorder::isRecording() const {
    return recordingActive.load();
}

const char *RawRecorder::getLastError() const {
    return errBuf;
}

int64_t RawRecorder::getCurrentTimeMs() const {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

ContainerFormat RawRecorder::autoSelectContainer() const {
    switch (srcFormat) {
        case VideoSourceFormat::MJPEG:
            return ContainerFormat::MOV;
        default:
            // Use MKV for raw/uncompressed formats.
            // MKV supports variable-frame-rate via PTS, which allows
            // wall-clock based A/V sync without audio trimming.
            // This keeps the audio waveform intact for external sync
            // (e.g., matching with OBS 7.1ch recording in DaVinci).
            return ContainerFormat::MKV;
    }
}

static size_t expected_video_frame_bytes(VideoSourceFormat fmt, int width, int height) {
    if (width <= 0 || height <= 0) {
        return 0;
    }
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    switch (fmt) {
        case VideoSourceFormat::YUYV:
        case VideoSourceFormat::UYVY:
            return pixels * 2;
        case VideoSourceFormat::NV12:
        case VideoSourceFormat::NV21:
            return (pixels * 3) / 2;
        case VideoSourceFormat::BGR24:
        case VideoSourceFormat::RGB24:
            return pixels * 3;
        case VideoSourceFormat::MJPEG:
        case VideoSourceFormat::UNKNOWN:
        default:
            return 0;
    }
}

static void put_le32(uint8_t *p, int32_t v) {
    uint32_t u = static_cast<uint32_t>(v);
    p[0] = static_cast<uint8_t>(u & 0xff);
    p[1] = static_cast<uint8_t>((u >> 8) & 0xff);
    p[2] = static_cast<uint8_t>((u >> 16) & 0xff);
    p[3] = static_cast<uint8_t>((u >> 24) & 0xff);
}

static int ebml_vint_length(uint8_t first) {
    for (int len = 1; len <= 8; ++len) {
        if (first & (0x80 >> (len - 1))) {
            return len;
        }
    }
    return 0;
}

static uint64_t ebml_vint_value(const uint8_t *p, int len) {
    uint64_t value = static_cast<uint64_t>(p[0] & (0xff >> len));
    for (int i = 1; i < len; ++i) {
        value = (value << 8) | p[i];
    }
    return value;
}

static void ebml_write_vint_value(uint8_t *p, int len, uint64_t value) {
    for (int i = len - 1; i >= 0; --i) {
        p[i] = static_cast<uint8_t>(value & 0xff);
        value >>= 8;
    }
    p[0] |= static_cast<uint8_t>(0x80 >> (len - 1));
}

static void patch_mkv_segment_size(const std::string &path) {
    if (path.empty()) return;
    int fd = ::open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGW("patch_mkv_segment_size: open failed for %s", path.c_str());
        return;
    }

    struct stat st {};
    if (::fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGW("patch_mkv_segment_size: fstat failed");
        ::close(fd);
        return;
    }

    uint8_t header[4096];
    ssize_t n = ::pread(fd, header, sizeof(header), 0);
    if (n <= 0) {
        LOGW("patch_mkv_segment_size: pread failed");
        ::close(fd);
        return;
    }

    int seg = -1;
    for (int i = 0; i + 4 < n; ++i) {
        if (header[i] == 0x18 && header[i + 1] == 0x53
            && header[i + 2] == 0x80 && header[i + 3] == 0x67) {
            seg = i;
            break;
        }
    }
    if (seg < 0 || seg + 5 >= n) {
        LOGW("patch_mkv_segment_size: Segment element not found");
        ::close(fd);
        return;
    }

    const int sizeOffset = seg + 4;
    const int vintLen = ebml_vint_length(header[sizeOffset]);
    if (vintLen <= 0 || sizeOffset + vintLen > n) {
        LOGW("patch_mkv_segment_size: invalid Segment size VINT");
        ::close(fd);
        return;
    }

    const int64_t segmentDataOffset = sizeOffset + vintLen;
    const uint64_t actualSize = static_cast<uint64_t>(st.st_size - segmentDataOffset);
    const uint64_t oldSize = ebml_vint_value(header + sizeOffset, vintLen);
    const uint64_t maxSize = (vintLen == 8) ? ((1ULL << 56) - 2) : ((1ULL << (7 * vintLen)) - 2);
    if (actualSize > maxSize) {
        LOGW("patch_mkv_segment_size: file too large for existing VINT len=%d", vintLen);
        ::close(fd);
        return;
    }

    if (oldSize != actualSize) {
        uint8_t out[8] = {};
        ebml_write_vint_value(out, vintLen, actualSize);
        if (::pwrite(fd, out, vintLen, sizeOffset) != vintLen) {
            LOGW("patch_mkv_segment_size: pwrite failed");
        } else {
            LOGI("patch_mkv_segment_size: %llu -> %llu at offset 0x%x",
                 (unsigned long long)oldSize, (unsigned long long)actualSize, sizeOffset);
        }
    } else {
        LOGI("patch_mkv_segment_size: already correct (%llu)", (unsigned long long)actualSize);
    }
    ::close(fd);
}

static bool patch_mkv_vfw_height_positive(const std::string &path, int width, int height) {
    if (path.empty() || width <= 0 || height <= 0) return false;
    int fd = ::open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGW("patch_mkv_vfw_height_positive: open failed for %s", path.c_str());
        return false;
    }

    constexpr size_t kScanBytes = 64 * 1024;
    std::vector<uint8_t> buf(kScanBytes);
    ssize_t n = ::pread(fd, buf.data(), buf.size(), 0);
    if (n <= 0) {
        LOGW("patch_mkv_vfw_height_positive: pread failed");
        ::close(fd);
        return false;
    }

    uint8_t wle[4], negHle[4], posHle[4];
    put_le32(wle, width);
    put_le32(negHle, -height);
    put_le32(posHle, height);

    for (ssize_t i = 0; i + 16 <= n; ++i) {
        // BITMAPINFOHEADER:
        //   biSize=40, biWidth, biHeight, biPlanes=1, biBitCount=24
        if (buf[i] == 0x28 && buf[i + 1] == 0x00 && buf[i + 2] == 0x00 && buf[i + 3] == 0x00
            && memcmp(buf.data() + i + 4, wle, 4) == 0
            && memcmp(buf.data() + i + 8, negHle, 4) == 0
            && buf[i + 12] == 0x01 && buf[i + 13] == 0x00
            && buf[i + 14] == 0x18 && buf[i + 15] == 0x00) {
            const off_t heightOffset = static_cast<off_t>(i + 8);
            if (::pwrite(fd, posHle, 4, heightOffset) == 4) {
                LOGI("patch_mkv_vfw_height_positive: patched VfW height -%d -> +%d at offset 0x%llx",
                     height, height, (long long)heightOffset);
                ::close(fd);
                return true;
            }
            LOGW("patch_mkv_vfw_height_positive: pwrite failed");
            ::close(fd);
            return false;
        }
    }

    LOGW("patch_mkv_vfw_height_positive: BITMAPINFOHEADER not found");
    ::close(fd);
    return false;
}

const char *RawRecorder::containerToExtension(ContainerFormat fmt) const {
    switch (fmt) {
        case ContainerFormat::AVI: return "avi";
        case ContainerFormat::MP4: return "mp4";
        case ContainerFormat::MOV: return "mov";
        case ContainerFormat::MKV: return "mkv";
        default: return "avi";
    }
}

const char *RawRecorder::containerToMuxer(ContainerFormat fmt) const {
    switch (fmt) {
        case ContainerFormat::AVI: return "avi";
        case ContainerFormat::MP4: return "mp4";
        case ContainerFormat::MOV: return "mov";
        case ContainerFormat::MKV: return "matroska";
        default: return "avi";
    }
}

void *RawRecorder::writerThreadEntry(void *arg) {
    RawRecorder *recorder = static_cast<RawRecorder *>(arg);
    recorder->writerLoop();
    return nullptr;
}

void RawRecorder::writerLoop() {
    int audioWrittenTotal = 0;
    int videoWrittenTotal = 0;

    LOGI("WriterLoop started");

    audioSamplesWritten = 0;
    videoFramesWritten = 0;
    lastSyncLogTimeMs = getCurrentTimeMs();

    // Raw PCM byte accumulator (FIFO).
    //
    // A/V SYNC STRATEGY (MKV + wall-clock PTS):
    // Both video and audio use wall-clock-derived PTS.  Audio is written
    // in full without any pacing or trimming, preserving the exact waveform
    // so it can be matched with external recordings (e.g., OBS 7.1ch).
    // MKV container honours per-packet PTS for playback timing, so even if
    // the actual video delivery rate differs from the nominal fps, the A/V
    // sync stays correct.
    std::vector<uint8_t> audioAccum;
    const int bpf = audioChannels * (audioBitsPerSample / 8);  // bytes per sample-frame

    for (;;) {
        pthread_mutex_lock(&queueMutex);
        while (!writerStopRequested && videoQueue.empty() && audioQueue.empty()) {
            pthread_cond_wait(&queueCond, &queueMutex);
        }

        if (writerStopRequested) {
            // ═══ FINAL DRAIN ═══
            while (!audioQueue.empty()) {
                AVPacket *p = audioQueue.front(); audioQueue.pop_front();
                if (p) { audioAccum.insert(audioAccum.end(), p->data, p->data + p->size); av_packet_free(&p); }
            }
            std::deque<AVPacket*> drainVideo;
            while (!videoQueue.empty()) {
                AVPacket *vp = videoQueue.front(); videoQueue.pop_front();
                if (vp) videoQueueBytes -= vp->size;
                drainVideo.push_back(vp);
            }
            pthread_mutex_unlock(&queueMutex);

            // 1) Write ALL remaining video
            for (AVPacket *vPkt : drainVideo) {
                if (vPkt) {
                    if (writeVideoPacket(vPkt) == 0) { videoWrittenTotal++; videoFramesWritten++; }
                    av_packet_free(&vPkt);
                }
            }

            // 2) Write ALL remaining audio (no trimming — preserve waveform)
            if (bpf > 0 && !audioAccum.empty()) {
                int64_t haveBytes = (int64_t)audioAccum.size();
                int64_t wb = (haveBytes / bpf) * bpf;
                int64_t ws = wb / bpf;
                if (wb > 0) {
                    AVPacket *pkt = av_packet_alloc();
                    if (pkt && av_new_packet(pkt, (int)wb) == 0) {
                        memcpy(pkt->data, audioAccum.data(), wb);
                        pkt->pts = audioSamplesWritten; pkt->dts = pkt->pts;
                        pkt->duration = ws;
                        pkt->stream_index = audioStream ? audioStream->index : 1;
                        pkt->flags |= AV_PKT_FLAG_KEY;
                        if (writeAudioPacket(pkt) == 0) { audioWrittenTotal++; audioSamplesWritten += ws; }
                        av_packet_free(&pkt);
                    } else if (pkt) { av_packet_free(&pkt); }
                }
            }

            audioAccum.clear();
            int64_t wallMs  = getCurrentTimeMs() - recordStartTimeMs;
            int64_t videoMs = (fpsNum > 0) ? (videoFramesWritten * 1000LL * fpsDen / fpsNum) : 0;
            int64_t audioMs = (audioSampleRate > 0) ? (audioSamplesWritten * 1000LL / audioSampleRate) : 0;
            LOGI("SYNC-FINAL wall=%lldms video=%lldms audio=%lldms diff=%lldms "
                 "frames=%lld samples=%lld",
                 (long long)wallMs, (long long)videoMs, (long long)audioMs,
                 (long long)(videoMs - audioMs),
                 (long long)videoFramesWritten, (long long)audioSamplesWritten);
            break;
        }

        // ═══ NORMAL PROCESSING ═══
        // Drain audio queue into byte accumulator
        while (!audioQueue.empty()) {
            AVPacket *p = audioQueue.front(); audioQueue.pop_front();
            if (p) { audioAccum.insert(audioAccum.end(), p->data, p->data + p->size); av_packet_free(&p); }
        }

        // Take one video packet
        AVPacket *videoPkt = nullptr;
        if (!videoQueue.empty()) {
            videoPkt = videoQueue.front(); videoQueue.pop_front();
            if (videoPkt) videoQueueBytes -= videoPkt->size;
        }
        pthread_mutex_unlock(&queueMutex);

        // ── Write VIDEO first ──
        if (videoPkt) {
            if (writeVideoPacket(videoPkt) == 0) { videoWrittenTotal++; videoFramesWritten++; }
            av_packet_free(&videoPkt);
        }

        // ── Write ALL available audio (no pacing, no trimming) ──
        // Audio waveform is preserved exactly as captured, which is
        // essential for waveform-based sync with external recordings
        // (e.g., OBS 7.1ch audio).  MKV container uses PTS for timing,
        // so A/V sync is maintained through wall-clock-derived timestamps
        // rather than forced duration matching.
        if (bpf > 0 && !audioAccum.empty()) {
            int64_t haveBytes = (int64_t)audioAccum.size();
            int64_t wb = (haveBytes / bpf) * bpf;
            int64_t ws = wb / bpf;
            if (wb > 0) {
                AVPacket *pkt = av_packet_alloc();
                if (pkt && av_new_packet(pkt, (int)wb) == 0) {
                    memcpy(pkt->data, audioAccum.data(), wb);
                    pkt->pts = audioSamplesWritten; pkt->dts = pkt->pts;
                    pkt->duration = ws;
                    pkt->stream_index = audioStream ? audioStream->index : 1;
                    pkt->flags |= AV_PKT_FLAG_KEY;
                    if (writeAudioPacket(pkt) == 0) { audioWrittenTotal++; audioSamplesWritten += ws; }
                    av_packet_free(&pkt);
                } else if (pkt) { av_packet_free(&pkt); }
                audioAccum.clear();
            }
        }

        // Periodic sync log
        int64_t now = getCurrentTimeMs();
        if ((now - lastSyncLogTimeMs) > 1000) {
            int64_t wallMs  = now - recordStartTimeMs;
            int64_t videoMs = (fpsNum > 0) ? (videoFramesWritten * 1000LL * fpsDen / fpsNum) : 0;
            int64_t audioMs = (audioSampleRate > 0) ? (audioSamplesWritten * 1000LL / audioSampleRate) : 0;
            LOGI("SYNC wall=%lldms v=%lldms a=%lldms diff=%lldms frames=%lld accum=%zuB",
                 (long long)wallMs, (long long)videoMs, (long long)audioMs,
                 (long long)(videoMs - audioMs),
                 (long long)videoFramesWritten, audioAccum.size());
            lastSyncLogTimeMs = now;
        }
    }

    LOGI("WriterLoop ended: audioWritten=%d videoWritten=%d", audioWrittenTotal, videoWrittenTotal);
}

int RawRecorder::writeVideoPacket(AVPacket *pkt) {
    if (!pkt) return -1;
    pthread_mutex_lock(&mutex);
    if (!fmtCtx || !videoStream) {
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    int ret = av_write_frame(fmtCtx, pkt);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Failed to write frame: %s", errbuf);
        snprintf(errBuf, sizeof(errBuf), "write frame failed: %s", errbuf);
        pthread_mutex_unlock(&mutex);
        return ret;
    }
    frameCount++;
    pthread_mutex_unlock(&mutex);
    return 0;
}

void RawRecorder::freeQueuedPackets() {
    pthread_mutex_lock(&queueMutex);
    while (!videoQueue.empty()) {
        AVPacket *pkt = videoQueue.front();
        videoQueue.pop_front();
        av_packet_free(&pkt);
    }
    videoQueueBytes = 0;
    while (!audioQueue.empty()) {
        AVPacket *pkt = audioQueue.front();
        audioQueue.pop_front();
        av_packet_free(&pkt);
    }
    pthread_mutex_unlock(&queueMutex);
}

int RawRecorder::setupStream(int width, int height, int fps) {
    if (!fmtCtx) return -1;
    const bool isAviMuxer = (fmtCtx->oformat && fmtCtx->oformat->name
                             && strstr(fmtCtx->oformat->name, "avi") != nullptr);
    const bool isMkvMuxer = (fmtCtx->oformat && fmtCtx->oformat->name
                             && strstr(fmtCtx->oformat->name, "matroska") != nullptr);

    AVCodecID codecId = AV_CODEC_ID_NONE;
    switch (srcFormat) {
        case VideoSourceFormat::MJPEG:
            codecId = AV_CODEC_ID_MJPEG;
            break;
        case VideoSourceFormat::YUYV:
        case VideoSourceFormat::UYVY:
        case VideoSourceFormat::NV12:
        case VideoSourceFormat::NV21:
        case VideoSourceFormat::BGR24:
        case VideoSourceFormat::RGB24:
            codecId = AV_CODEC_ID_RAWVIDEO;
            break;
        default:
            LOGE("Unknown source format, cannot setup stream");
            return -1;
    }

    videoStream = avformat_new_stream(fmtCtx, nullptr);
    if (!videoStream) {
        LOGE("Failed to create new stream");
        return -1;
    }

    AVCodecParameters *par = videoStream->codecpar;
    par->codec_id = codecId;
    par->codec_type = AVMEDIA_TYPE_VIDEO;
    par->width = width;
    par->height = height;
    par->format = AV_PIX_FMT_NONE;
    par->sample_aspect_ratio = (AVRational){1, 1};

    // MKV uses V_MS/VFW/FOURCC mode for rawvideo, which still requires
    // a valid codec_tag (FourCC) embedded in the BITMAPINFOHEADER.
    // So we set codec_tag for ALL muxers (AVI, MKV, MOV).
    if (srcFormat == VideoSourceFormat::MJPEG) {
        par->codec_tag = MKTAG('M', 'J', 'P', 'G');
    } else if (srcFormat == VideoSourceFormat::YUYV) {
        par->codec_tag = MKTAG('Y', 'U', 'Y', '2');
        par->format = AV_PIX_FMT_YUYV422;
        par->bits_per_coded_sample = 16;
        par->block_align = width * height * 2;
    } else if (srcFormat == VideoSourceFormat::UYVY) {
        par->codec_tag = MKTAG('U', 'Y', 'V', 'Y');
        par->format = AV_PIX_FMT_UYVY422;
        par->bits_per_coded_sample = 16;
        par->block_align = width * height * 2;
    } else if (srcFormat == VideoSourceFormat::NV12) {
        par->codec_tag = MKTAG('N', 'V', '1', '2');
        par->format = AV_PIX_FMT_NV12;
        par->bits_per_coded_sample = 12;
        par->block_align = width * height * 3 / 2;
    } else if (srcFormat == VideoSourceFormat::NV21) {
        par->codec_tag = MKTAG('N', 'V', '2', '1');
        par->format = AV_PIX_FMT_NV21;
        par->bits_per_coded_sample = 12;
        par->block_align = width * height * 3 / 2;
    } else if (srcFormat == VideoSourceFormat::BGR24) {
        par->format = AV_PIX_FMT_BGR24;
        par->bits_per_coded_sample = 24;
        // Use 'DIB ' for AVI/MKV (VfW compat), 'BGR ' for MOV
        par->codec_tag = (isAviMuxer || isMkvMuxer)
                         ? MKTAG('D', 'I', 'B', ' ')
                         : MKTAG('B', 'G', 'R', ' ');
        par->block_align = width * height * 3;
    } else if (srcFormat == VideoSourceFormat::RGB24) {
        par->format = AV_PIX_FMT_RGB24;
        par->bits_per_coded_sample = 24;
        par->codec_tag = MKTAG('R', 'G', 'B', ' ');
        par->block_align = width * height * 3;
    }

    // Timebase will be set in start() after audio stream is configured
    // to ensure a unified timebase for both streams
    videoStream->avg_frame_rate = (AVRational){fpsNum, fpsDen};
    videoStream->r_frame_rate = (AVRational){fpsNum, fpsDen};
    videoStream->duration = 0;
    // Set exact bit_rate for raw video so MediaInfo shows precise Bits/(Pixel*Frame)
    if (codecId == AV_CODEC_ID_RAWVIDEO && par->bits_per_coded_sample > 0) {
        par->bit_rate = (int64_t)par->bits_per_coded_sample * width * height * fpsNum / fpsDen;
    } else {
        par->bit_rate = 0;
    }

    return 0;
}

int RawRecorder::start(const char *path,
                          int width, int height, int fps,
                          VideoSourceFormat srcFmt,
                          ContainerFormat container,
                          int aSampleRate,
                          int aChannels,
                          int aBitsPerSample) {
    pthread_mutex_lock(&mutex);

    if (fmtCtx) {
        LOGE("Already recording");
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    outputPath = path ? path : "";
    srcFormat = srcFmt;
    videoWidth = width;
    videoHeight = height;
    headerWritten = false;

    if (container == ContainerFormat::AUTO) {
        container = autoSelectContainer();
    }

    const char *muxerName = containerToMuxer(container);
    LOGI("start(): path=%s muxer=%s container=%d srcFmt=%d",
         outputPath.c_str(), muxerName, (int)container, (int)srcFmt);

    int ret = avformat_alloc_output_context2(&fmtCtx, nullptr, muxerName, outputPath.c_str());
    if (ret < 0 || !fmtCtx) {
        LOGE("Failed to allocate output context for muxer '%s': %d", muxerName, ret);
        snprintf(errBuf, sizeof(errBuf), "avformat_alloc_output_context2 failed: %d", ret);
        cleanup();
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    fmtCtx->max_delay = 0;

    // Set fps BEFORE setupStream so avg_frame_rate and time_base use correct values
    fpsNum = fps;
    fpsDen = 1;
    LOGI("FPS set: fpsNum=%d fpsDen=%d", fpsNum, fpsDen);

    if (setupStream(width, height, fps) < 0) {
        LOGE("Failed to setup video stream");
        snprintf(errBuf, sizeof(errBuf), "setupStream failed");
        cleanup();
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    bool audioRequested = (aSampleRate > 0 && aChannels > 0 && aBitsPerSample > 0);
    if (audioRequested) {
        pthread_mutex_unlock(&mutex);
        int aret = setupAudioStream(aSampleRate, aChannels, aBitsPerSample);
        pthread_mutex_lock(&mutex);
        if (aret != 0) {
            LOGW("setupAudioStream failed (%d); continuing video-only", aret);
        } else {
            LOGI("Audio stream registered: %dHz / %dch / %dbits",
                 aSampleRate, aChannels, aBitsPerSample);
        }
    }

    // Set stream timebases and PTS strategy based on container type.
    // We use av_write_frame (not av_interleaved_write_frame) so cross-stream
    // duration rescaling (which could truncate audio duration to 0) is avoided.
    activeContainer = container;
    useWallClockPts = (container == ContainerFormat::MKV);
    mkvVfwPositiveHeight = (container == ContainerFormat::MKV
                            && (srcFmt == VideoSourceFormat::BGR24
                                || srcFmt == VideoSourceFormat::RGB24));

    if (videoStream) {
        if (useWallClockPts) {
            // MKV: use 1/1000 (ms) timebase for wall-clock PTS.
            // This gives per-frame timing that tracks real delivery rate,
            // keeping A/V sync correct even if actual fps ≠ nominal fps.
            videoStream->time_base = (AVRational){1, 1000};
            LOGI("Video time_base set to 1/1000 (wall-clock ms) for MKV");
        } else {
            // AVI/MOV: use frame-rate timebase (e.g. 1/60).
            // AVI muxer needs this for dwRate/dwScale in the header.
            videoStream->time_base = (AVRational){fpsDen, fpsNum};
            LOGI("Video time_base set to %d/%d = 1/%d fps",
                 fpsDen, fpsNum, fps);
        }
    }
    if (audioStream) {
        // Audio always uses 1/sampleRate so PTS = sample count.
        LOGI("Audio time_base is 1/%d (sample rate)", audioSampleRate);
    }

    if (!(fmtCtx->flags & AVFMT_NOFILE)) {
        ret = avio_open(&fmtCtx->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
        if (ret < 0) {
            char errbuf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
            LOGE("Failed to open output file: %s", errbuf);
            snprintf(errBuf, sizeof(errBuf), "avio_open failed: %s", errbuf);
            cleanup();
            pthread_mutex_unlock(&mutex);
            return -1;
        }
    }

    LOGI("About to write header: video codec_id=%d codec_tag=0x%08x pix_fmt=%d, audio=%s",
         videoStream ? (int)videoStream->codecpar->codec_id : -1,
         videoStream ? videoStream->codecpar->codec_tag : 0,
         videoStream ? videoStream->codecpar->format : -1,
         audioStream ? "yes" : "no");

    AVDictionary *headerOptions = nullptr;
    if (container == ContainerFormat::MKV
        && videoStream
        && videoStream->codecpar
        && videoStream->codecpar->codec_id == AV_CODEC_ID_RAWVIDEO) {
        // Matroska refuses raw RGB/BGR by default and returns EINVAL unless
        // this muxer option is enabled.  With allow_raw_vfw=1, FFmpeg stores
        // rawvideo as V_MS/VFW/FOURCC using the codec_tag/BITMAPINFOHEADER.
        av_dict_set(&headerOptions, "allow_raw_vfw", "1", 0);
        // Do NOT set Matroska "live=1" here.  Live mode omits the Info
        // Duration element, so MediaInfo/ffprobe cannot report a real file
        // duration and may estimate a bogus duration from bitrate.  We keep
        // normal (seekable) Matroska output and repair Segment size after
        // closing if necessary.
        LOGI("MKV rawvideo: set muxer option allow_raw_vfw=1");
    }

    ret = avformat_write_header(fmtCtx, &headerOptions);
    if (headerOptions) {
        AVDictionaryEntry *e = nullptr;
        while ((e = av_dict_get(headerOptions, "", e, AV_DICT_IGNORE_SUFFIX)) != nullptr) {
            LOGW("Unused muxer option after avformat_write_header: %s=%s", e->key, e->value);
        }
        av_dict_free(&headerOptions);
    }
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Failed to write header: %s (muxer=%s)", errbuf, fmtCtx->oformat->name);
        snprintf(errBuf, sizeof(errBuf), "avformat_write_header failed: %s", errbuf);
        cleanup();
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    headerWritten = true;

    frameCount = 0;
    queuedVideoCount = 0;
    droppedVideoCount = 0;
    startTimeMs = av_gettime() / 1000;
    recordStartTimeMs = getCurrentTimeMs();
    // fpsNum/fpsDen already set before setupStream
    videoStreamIndex = videoStream ? videoStream->index : -1;
    expectedVideoFrameBytes = expected_video_frame_bytes(srcFormat, width, height);
    
    // Initialize audio-video sync variables
    firstVideoTimeMs = 0;
    firstAudioTimeMs = 0;
    audioTimeOffsetSamples = 0;

    pthread_mutex_lock(&queueMutex);
    writerStopRequested = false;
    videoQueueBytes = 0;
    pthread_mutex_unlock(&queueMutex);
    ret = pthread_create(&writerThread, nullptr, writerThreadEntry, this);
    if (ret != 0) {
        LOGE("Failed to start writer thread: %d", ret);
        snprintf(errBuf, sizeof(errBuf), "pthread_create writer failed: %d", ret);
        cleanup();
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    writerThreadStarted = true;
    recordingActive.store(true);

    LOGI("Started recording to %s (format=%s, source=%d, %dx%d@%d, audio=%s)",
         outputPath.c_str(), muxerName, static_cast<int>(srcFmt), width, height, fps,
         audioStream ? "yes" : "no");

    pthread_mutex_unlock(&mutex);
    return 0;
}

void RawRecorder::stop() {
    if (!recordingActive.exchange(false) && !fmtCtx) {
        return;
    }

    pthread_mutex_lock(&queueMutex);
    writerStopRequested = true;
    pthread_cond_signal(&queueCond);
    pthread_mutex_unlock(&queueMutex);

    if (writerThreadStarted) {
        pthread_join(writerThread, nullptr);
        writerThreadStarted = false;
    }

    pthread_mutex_lock(&mutex);

    if (!fmtCtx) {
        pthread_mutex_unlock(&mutex);
        return;
    }

    LOGI("RawRecorder::stop: stopping recording, frameCount=%ld queued=%ld dropped=%ld",
         frameCount, queuedVideoCount, droppedVideoCount);

    if (fmtCtx->pb) {
        avio_flush(fmtCtx->pb);
    }

    int ret = av_write_trailer(fmtCtx);
    LOGI("RawRecorder::stop: av_write_trailer returned %d", ret);

    const bool shouldPatchMkvAfterClose = (activeContainer == ContainerFormat::MKV);

    // AVI-only legacy header patch:
    // offset 48 is dwTotalFrames in an AVI header.  Never write this into
    // MKV/MOV/MP4 — doing so corrupts the EBML/ISO container header and
    // causes MediaInfo errors such as "File size is less than expected" and
    // bogus dimensions (e.g. height shown as 4294966216).
    if (activeContainer == ContainerFormat::AVI
        && fmtCtx->pb && !(fmtCtx->flags & AVFMT_NOFILE)) {
        int64_t fileSize = avio_size(fmtCtx->pb);
        if (fileSize > 0 && frameCount > 0) {
            int64_t curPos = avio_tell(fmtCtx->pb);
            avio_seek(fmtCtx->pb, 48, SEEK_SET);
            avio_wl32(fmtCtx->pb, (unsigned int)frameCount);
            avio_seek(fmtCtx->pb, curPos, SEEK_SET);
            avio_flush(fmtCtx->pb);
            LOGI("RawRecorder::stop: updated dwTotalFrames to %ld", frameCount);
        }
    }

    if (fmtCtx->pb) {
        avio_flush(fmtCtx->pb);
    }

    LOGI("Stopped recording to %s, wrote %ld frames, dropped %ld",
         outputPath.c_str(), frameCount, droppedVideoCount);

    cleanup();

    if (shouldPatchMkvAfterClose) {
        patch_mkv_segment_size(outputPath);
        if (mkvVfwPositiveHeight) {
            patch_mkv_vfw_height_positive(outputPath, videoWidth, videoHeight);
        }
    }
    pthread_mutex_unlock(&mutex);
}

void RawRecorder::cleanup() {
    recordingActive.store(false);
    freeQueuedPackets();
    if (fmtCtx) {
        if (!(fmtCtx->flags & AVFMT_NOFILE) && fmtCtx->pb) {
            avio_closep(&fmtCtx->pb);
        }
        avformat_free_context(fmtCtx);
        fmtCtx = nullptr;
    }
    videoStream = nullptr;
    audioStream = nullptr;
    frameCount = 0;
    audioSampleCount = 0;
    headerWritten = false;
    videoStreamIndex = -1;
    expectedVideoFrameBytes = 0;
}

int RawRecorder::setupAudioStream(int sampleRate, int channels, int bitsPerSample) {
    pthread_mutex_lock(&mutex);

    if (!fmtCtx) {
        LOGE("Cannot setup audio stream: not recording");
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    if (headerWritten) {
        LOGE("setupAudioStream called after header write -- refusing");
        snprintf(errBuf, sizeof(errBuf),
                 "setupAudioStream after header write is forbidden");
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    if (audioStream) {
        LOGI("Audio stream already setup");
        pthread_mutex_unlock(&mutex);
        return 0;
    }

    audioStream = avformat_new_stream(fmtCtx, nullptr);
    if (!audioStream) {
        LOGE("Failed to create audio stream");
        snprintf(errBuf, sizeof(errBuf), "avformat_new_stream for audio failed");
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    AVCodecParameters *par = audioStream->codecpar;
    par->codec_type = AVMEDIA_TYPE_AUDIO;
    par->sample_rate = sampleRate;

    uint64_t legacyMask = 0;
    AVChannelLayout newLayout;
    av_channel_layout_default(&newLayout, channels);
    switch (channels) {
        case 1:
            legacyMask = AV_CH_LAYOUT_MONO; break;
        case 2:
            legacyMask = AV_CH_LAYOUT_STEREO; break;
        case 6:
            legacyMask = AV_CH_LAYOUT_5POINT1; break;
        case 8:
            legacyMask = AV_CH_LAYOUT_7POINT1; break;
        default:
            legacyMask = 0; break;
    }

    #if LIBAVCODEC_VERSION_MAJOR >= 59
        if (legacyMask) {
            av_channel_layout_uninit(&newLayout);
            av_channel_layout_from_mask(&newLayout, legacyMask);
        }
        par->ch_layout = newLayout;
    #else
        par->channels = channels;
        par->channel_layout = legacyMask;
        av_channel_layout_uninit(&newLayout);
    #endif

    par->bits_per_coded_sample = bitsPerSample;
    par->bit_rate = sampleRate * channels * bitsPerSample;

    // For 24-bit: USB sends packed 24-bit (3 bytes per sample), FFmpeg uses S32 with lower 3 bytes
    // AVI/MP4 container stores PCM_S24LE which is packed 24-bit little endian
    if (bitsPerSample == 24) {
        par->codec_id = AV_CODEC_ID_PCM_S24LE;
        par->format = AV_SAMPLE_FMT_S32;  // FFmpeg stores 24-bit in 32-bit containers
    } else if (bitsPerSample == 16) {
        par->codec_id = AV_CODEC_ID_PCM_S16LE;
        par->format = AV_SAMPLE_FMT_S16;
    } else {
        par->codec_id = AV_CODEC_ID_PCM_U8;
        par->format = AV_SAMPLE_FMT_U8;
    }

    par->block_align = channels * (bitsPerSample / 8);
    // Audio timebase: use sample rate so PTS = sample count
    audioStream->time_base = (AVRational){1, sampleRate};

    audioSampleRate = sampleRate;
    audioChannels = channels;
    audioBitsPerSample = bitsPerSample;

    LOGI("Audio stream setup: sampleRate=%d, channels=%d, bitsPerSample=%d, codec_id=%d, time_base=1/%d",
         sampleRate, channels, bitsPerSample, (int)par->codec_id, sampleRate);

    pthread_mutex_unlock(&mutex);
    return 0;
}

int RawRecorder::writeAudio(const uint8_t *data, size_t len, int64_t pts) {
    if (!recordingActive.load()) {
        static int nrCount = 0;
        if (++nrCount <= 3) LOGW("writeAudio: not recording #%d", nrCount);
        return -1;
    }

    static int totalCalls = 0;
    totalCalls++;
    if (totalCalls <= 10 || totalCalls % 500 == 0) {
        LOGI("writeAudio: call=%d len=%zu active=%d audioStream=%p",
             totalCalls, len, recordingActive.load(), (void*)audioStream);
    }

    int bytesPerSample = audioBitsPerSample / 8;
    if (bytesPerSample <= 0) {
        LOGE("writeAudio: invalid bytesPerSample=%d", bytesPerSample);
        return -1;
    }
    int sampleCount = static_cast<int>(len / (audioChannels * bytesPerSample));
    if (sampleCount <= 0) {
        LOGW("writeAudio: sampleCount=%d len=%zu channels=%d bytesPerSample=%d",
             sampleCount, len, audioChannels, bytesPerSample);
        return 0;
    }

    AVPacket *pkt = av_packet_alloc();
    if (!pkt) {
        LOGE("writeAudio: av_packet_alloc failed");
        return -1;
    }

    int ret = av_new_packet(pkt, static_cast<int>(len));
    if (ret < 0) {
        LOGE("writeAudio: av_new_packet failed: %d", ret);
        av_packet_free(&pkt);
        return -1;
    }

    memcpy(pkt->data, data, len);
    pkt->flags |= AV_PKT_FLAG_KEY;

    // Store wall-clock enqueue time in opaque for latency measurement
    int64_t audioEnqueueTimeMs = getCurrentTimeMs();
    pkt->opaque = reinterpret_cast<void*>(static_cast<intptr_t>(audioEnqueueTimeMs));

    // Record first audio data arrival time for audio-video sync
    if (firstAudioTimeMs == 0) {
        firstAudioTimeMs = audioEnqueueTimeMs;
        LOGI("First audio data arrived at %lld ms", (long long)firstAudioTimeMs);
        
        // Calculate audio time offset to align with video
        if (firstVideoTimeMs > 0 && audioSampleRate > 0) {
            int64_t delayMs = firstAudioTimeMs - firstVideoTimeMs;
            if (delayMs > 0) {
                // Audio arrived later than video, calculate offset in samples
                audioTimeOffsetSamples = (delayMs * audioSampleRate) / 1000;
                LOGI("Audio-video sync: audio delayed by %lld ms = %lld samples",
                     (long long)delayMs, (long long)audioTimeOffsetSamples);
            } else {
                LOGI("Audio-video sync: audio arrived before video by %lld ms", (long long)(-delayMs));
            }
        }
    }

    // Push to audio queue (non-blocking — never stalls the USB callback)
    pthread_mutex_lock(&queueMutex);
    if (writerStopRequested) {
        pthread_mutex_unlock(&queueMutex);
        av_packet_free(&pkt);
        LOGW("writeAudio: writerStopRequested, dropping packet");
        return -1;
    }
    if (audioQueue.size() > 1000) {
        // Audio queue getting too large, drop oldest packets
        LOGW("writeAudio: audioQueue too large (%zu), dropping oldest", audioQueue.size());
        while (audioQueue.size() > 500) {
            AVPacket *old = audioQueue.front();
            audioQueue.pop_front();
            if (old) av_packet_free(&old);
        }
    }
    // Use raw sample count as PTS - simplest and most accurate for PCM audio
    // Apply audio-video sync offset to align with video timestamp
    int64_t audioPts = audioSampleCount + audioTimeOffsetSamples;
    int64_t audioDur = sampleCount;

    // Log sync status periodically (every 30 seconds)
    int64_t currentTimeMs = getCurrentTimeMs();
    if (currentTimeMs - lastSyncLogTimeMs > 30000) {
        lastSyncLogTimeMs = currentTimeMs;
        int64_t recordingTimeSec = (currentTimeMs - recordStartTimeMs) / 1000;
        LOGI("Audio-video sync status (t=%llds): audioSamples=%lld, videoFrames=%lld, audioOffset=%lld samples",
             (long long)recordingTimeSec, (long long)audioSampleCount, 
             (long long)queuedVideoCount, (long long)audioTimeOffsetSamples);
        
        // Calculate expected audio samples based on recording time
        if (audioSampleRate > 0) {
            int64_t expectedAudioSamples = recordingTimeSec * audioSampleRate;
            int64_t audioSampleDiff = audioSampleCount - expectedAudioSamples;
            if (audioSampleDiff != 0) {
                LOGI("Audio sample drift: %lld samples (%lld ms)", 
                     (long long)audioSampleDiff, (long long)(audioSampleDiff * 1000 / audioSampleRate));
            }
        }
    }

    pkt->pts = audioPts;
    pkt->dts = audioPts;
    pkt->duration = audioDur;

    if (!audioStream) {
        LOGE("writeAudio: audioStream is null! stream_index will be invalid");
        pkt->stream_index = 1;
    } else {
        pkt->stream_index = audioStream->index;
    }

    // Log EVERY audio packet to diagnose early stop
    static int audioPktCount = 0;
    audioPktCount++;
    if (audioPktCount <= 50 || audioPktCount % 500 == 0 || audioQueue.size() > 100) {
        LOGI("writeAudio: pkt=%d/%d samples=%d len=%zu pts=%ld dur=%ld queue=%zu",
             audioPktCount, audioSampleCount/sampleCount, sampleCount, len, pkt->pts, audioDur, audioQueue.size());
    }

    audioSampleCount += sampleCount;
    audioQueue.push_back(pkt);
    pthread_cond_signal(&queueCond);
    pthread_mutex_unlock(&queueMutex);
    return 0;
}

int RawRecorder::writeAudioPacket(AVPacket *pkt) {
    if (!pkt) return -1;
    pthread_mutex_lock(&mutex);
    if (!fmtCtx || !audioStream) {
        pthread_mutex_unlock(&mutex);
        return -1;
    }

    // writerLoop creates audio packet PTS/duration in SAMPLE units to preserve
    // the exact captured waveform.  av_write_frame requires packet timestamps
    // in audioStream->time_base.  Matroska commonly rewrites audio time_base
    // to 1/1000 after avformat_write_header; if we pass sample-count PTS
    // directly, 10 seconds at 96 kHz is interpreted as 960000 milliseconds
    // (~16 minutes).  Rescale here using cumulative sample positions, and
    // compute duration from end-start to avoid long-term rounding drift.
    if (audioSampleRate > 0) {
        const AVRational sampleTb = (AVRational){1, audioSampleRate};
        int64_t sampleStart = pkt->pts;
        int64_t sampleDur = pkt->duration;
        if (sampleStart == AV_NOPTS_VALUE) {
            sampleStart = 0;
        }
        if (sampleDur < 0) {
            sampleDur = 0;
        }
        const int64_t tsStart = av_rescale_q(sampleStart, sampleTb, audioStream->time_base);
        const int64_t tsEnd = av_rescale_q(sampleStart + sampleDur, sampleTb, audioStream->time_base);
        pkt->pts = tsStart;
        pkt->dts = tsStart;
        pkt->duration = std::max<int64_t>(1, tsEnd - tsStart);
    }

    int ret = av_write_frame(fmtCtx, pkt);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
        LOGE("Failed to write audio frame: %s", errbuf);
    }
    pthread_mutex_unlock(&mutex);
    return ret;
}

int RawRecorder::writeFrame(const uint8_t *data, size_t len, int64_t pts) {
    if (!recordingActive.load() || !data || len == 0 || videoStreamIndex < 0) {
        return -1;
    }

    if (expectedVideoFrameBytes > 0 && len != expectedVideoFrameBytes) {
        static int malformedFrameLogCount = 0;
        malformedFrameLogCount++;
        if (malformedFrameLogCount <= 8 || (malformedFrameLogCount % 120) == 0) {
            LOGW("drop malformed raw frame: len=%u expected=%u fmt=%d",
                 static_cast<unsigned int>(len),
                 static_cast<unsigned int>(expectedVideoFrameBytes),
                 static_cast<int>(srcFormat));
        }
        return -1;
    }

    static int acceptedFrameLogCount = 0;
    acceptedFrameLogCount++;
    if (acceptedFrameLogCount <= 5 || (acceptedFrameLogCount % 300) == 0) {
        LOGI("accept raw frame #%d: len=%u expected=%u fmt=%d",
             acceptedFrameLogCount,
             static_cast<unsigned int>(len),
             static_cast<unsigned int>(expectedVideoFrameBytes),
             static_cast<int>(srcFormat));
    }

    AVPacket *pkt = av_packet_alloc();
    if (!pkt) {
        return -1;
    }

    int ret = av_new_packet(pkt, static_cast<int>(len));
    if (ret < 0) {
        av_packet_free(&pkt);
        return -1;
    }

    if (mkvVfwPositiveHeight
        && (srcFormat == VideoSourceFormat::BGR24 || srcFormat == VideoSourceFormat::RGB24)
        && videoWidth > 0 && videoHeight > 0
        && len == static_cast<size_t>(videoWidth) * static_cast<size_t>(videoHeight) * 3) {
        // After patching the MKV VfW BITMAPINFOHEADER to a positive height,
        // the DIB is interpreted as bottom-up.  UVC RGB/BGR frames arrive
        // top-down, so store rows in reverse order to keep playback upright
        // while MediaInfo reports a normal positive height.
        const size_t rowBytes = static_cast<size_t>(videoWidth) * 3;
        for (int y = 0; y < videoHeight; ++y) {
            memcpy(pkt->data + static_cast<size_t>(y) * rowBytes,
                   data + static_cast<size_t>(videoHeight - 1 - y) * rowBytes,
                   rowBytes);
        }
    } else {
        memcpy(pkt->data, data, len);
    }

    // Store wall-clock enqueue time in opaque for latency measurement
    int64_t videoEnqueueTimeMs = getCurrentTimeMs();
    pkt->opaque = reinterpret_cast<void*>(static_cast<intptr_t>(videoEnqueueTimeMs));

    // Record first video frame arrival time for audio-video sync
    if (firstVideoTimeMs == 0) {
        firstVideoTimeMs = videoEnqueueTimeMs;
        LOGI("First video frame arrived at %lld ms", (long long)firstVideoTimeMs);
    }

    int64_t seq = __sync_fetch_and_add(&queuedVideoCount, 1);

    if (useWallClockPts) {
        // MKV: wall-clock PTS in milliseconds (timebase = 1/1000).
        // This tracks actual frame delivery timing, so A/V sync is
        // correct even if real fps differs from nominal fps.
        int64_t elapsedMs = videoEnqueueTimeMs - recordStartTimeMs;
        if (elapsedMs < 0) elapsedMs = 0;
        pkt->pts = elapsedMs;
        pkt->dts = elapsedMs;
        pkt->duration = (fpsNum > 0) ? (1000 * fpsDen / fpsNum) : 16; // nominal frame duration in ms
    } else {
        // AVI/MOV: frame-count PTS (timebase = 1/fps).
        if (pts < 0) {
            pkt->pts = seq;
            pkt->dts = seq;
        } else {
            pkt->pts = (pts * fpsNum) / (1000 * fpsDen);
            pkt->dts = pkt->pts;
        }
        pkt->duration = 1;
    }
    pkt->stream_index = videoStreamIndex;
    pkt->flags |= AV_PKT_FLAG_KEY;

    pthread_mutex_lock(&queueMutex);
    if (writerStopRequested || !recordingActive.load()) {
        pthread_mutex_unlock(&queueMutex);
        av_packet_free(&pkt);
        return -1;
    }
    while ((!videoQueue.empty())
           && (videoQueue.size() >= maxVideoQueuePackets
               || videoQueueBytes + static_cast<size_t>(pkt->size) > maxVideoQueueBytes)) {
        AVPacket *old = videoQueue.front();
        videoQueue.pop_front();
        if (old) {
            videoQueueBytes -= old->size;
            av_packet_free(&old);
            droppedVideoCount++;
        }
    }
    videoQueueBytes += pkt->size;
    videoQueue.push_back(pkt);
    pthread_cond_signal(&queueCond);
    pthread_mutex_unlock(&queueMutex);
    return 0;
}

} // namespace uvc
