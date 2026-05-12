/*
 * RawRecorder.h
 *
 * FFmpeg-based raw bitstream recorder for UVC capture cards.
 * Supports source format passthrough (MJPEG/YUY2/GBR24/...) to AVI/MP4/MOV/MKV.
 */

#ifndef RAW_RECORDER_H
#define RAW_RECORDER_H

#include <pthread.h>
#include <stdint.h>
#include <string>
#include <deque>
#include <atomic>
#include <chrono>

// FFmpeg forward declarations (opaque pointers)
struct AVFormatContext;
struct AVStream;
struct AVPacket;

namespace uvc {

// Container format selection
enum class ContainerFormat {
    AVI,
    MP4,
    MOV,
    MKV,
    AUTO        // Choose based on video format
};

// Video source format (matches UVC frame types)
enum class VideoSourceFormat {
    MJPEG,
    YUYV,
    UYVY,
    NV12,
    NV21,
    BGR24,
    RGB24,
    UNKNOWN
};

/**
 * RawRecorder - records raw UVC frames into container files using FFmpeg.
 *
 * This class is thread-safe for start/stop/writeFrame calls.
 */
class RawRecorder {
public:
    RawRecorder();
    ~RawRecorder();

    int start(const char *path,
              int width, int height, int fps,
              VideoSourceFormat srcFmt,
              ContainerFormat container = ContainerFormat::AUTO,
              int audioSampleRate = 0,
              int audioChannels = 0,
              int audioBitsPerSample = 0);

    void stop();

    int writeFrame(const uint8_t *data, size_t len, int64_t pts = -1);

    int setupAudioStream(int sampleRate, int channels, int bitsPerSample);

    int writeAudio(const uint8_t *data, size_t len, int64_t pts);

    bool isRecording() const;

    const char *getLastError() const;

    // Stats for UI overlay
    int64_t getVideoFramesWritten() const { return videoFramesWritten; }
    int64_t getAudioSamplesWritten() const { return audioSamplesWritten; }
    int64_t getDroppedVideoCount() const { return droppedVideoCount; }
    std::string getOutputPath() const { return outputPath; }

private:
    AVFormatContext *fmtCtx = nullptr;
    AVStream *videoStream = nullptr;
    AVStream *audioStream = nullptr;
    int64_t frameCount = 0;
    int64_t audioSampleCount = 0;
    int64_t startTimeMs = 0;
    int fpsNum = 30;
    int fpsDen = 1;
    int videoWidth = 0;
    int videoHeight = 0;
    VideoSourceFormat srcFormat = VideoSourceFormat::UNKNOWN;
    ContainerFormat activeContainer = ContainerFormat::AVI;
    bool useWallClockPts = false;  // true for MKV (VFR-capable containers)
    bool mkvVfwPositiveHeight = false; // true when MKV VfW header was patched to positive height
    std::string outputPath;
    char errBuf[256];
    std::atomic<bool> recordingActive{false};

    int audioSampleRate = 0;
    int audioChannels = 0;
    int audioBitsPerSample = 0;
    bool headerWritten = false;

    mutable pthread_mutex_t mutex;
    pthread_mutex_t queueMutex;
    pthread_cond_t queueCond;
    pthread_t writerThread;
    bool writerThreadStarted = false;
    bool writerStopRequested = false;
    std::deque<AVPacket *> videoQueue;
    std::deque<AVPacket *> audioQueue;
    size_t videoQueueBytes = 0;
    size_t maxVideoQueueBytes = 96 * 1024 * 1024;
    size_t maxVideoQueuePackets = 120;
    int videoStreamIndex = -1;
    size_t expectedVideoFrameBytes = 0;
    int64_t queuedVideoCount = 0;
    int64_t droppedVideoCount = 0;
    int64_t recordStartTimeMs = 0;
    int64_t audioSamplesWritten = 0;   // cumulative audio samples written to file
    int64_t videoFramesWritten = 0;    // cumulative video frames written to file
    int64_t lastSyncLogTimeMs = 0;     // last time sync log was printed
    
    // Audio-video sync: record first video and audio arrival times
    int64_t firstVideoTimeMs = 0;      // wall-clock time of first video frame
    int64_t firstAudioTimeMs = 0;      // wall-clock time of first audio data
    int64_t audioTimeOffsetSamples = 0; // audio PTS offset to align with video

    int64_t getCurrentTimeMs() const;

    ContainerFormat autoSelectContainer() const;
    const char *containerToExtension(ContainerFormat fmt) const;
    const char *containerToMuxer(ContainerFormat fmt) const;
    int setupStream(int width, int height, int fps);
    static void *writerThreadEntry(void *arg);
    void writerLoop();
    int writeVideoPacket(AVPacket *pkt);
    int writeAudioPacket(AVPacket *pkt);
    void freeQueuedPackets();
    void cleanup();
};

} // namespace uvc

#endif // RAW_RECORDER_H
