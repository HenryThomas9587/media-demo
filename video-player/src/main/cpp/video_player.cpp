#include <jni.h>
#include <android/log.h>
#include <queue>
#include <thread>
#include <condition_variable>
#include <mutex>
#include <memory>
#include <atomic>
#include <algorithm>
#include <limits>

extern "C" {
#if defined(__arm64__) || defined(__aarch64__)  // 针对 arm64-v8a 架构
#include "ffmpeg/arm64-v8a/include/libavformat/avformat.h"
#include "ffmpeg/arm64-v8a/include/libavcodec/avcodec.h"
#include "ffmpeg/arm64-v8a/include/libavutil/frame.h"
#include "ffmpeg/arm64-v8a/include/libavutil/imgutils.h"
#include "ffmpeg/arm64-v8a/include/libavutil/time.h"
#elif defined(__x86_64__)  // 针对 x86_64 架构
#include "ffmpeg/x86_64/include/libavformat/avformat.h"
#include "ffmpeg/x86_64/include/libavcodec/avcodec.h"
#include "ffmpeg/x86_64/include/libavutil/frame.h"
#include "ffmpeg/x86_64/include/libavutil/imgutils.h"
#include "ffmpeg/x86_64/include/libavutil/time.h"
#else
// 默认使用通用的头文件
#include "ffmpeg/include/libavformat/avformat.h"
#include "ffmpeg/include/libavcodec/avcodec.h"
#include "ffmpeg/include/libavutil/frame.h"
#include "ffmpeg/include/libavutil/imgutils.h"
#include "ffmpeg/include/libavutil/time.h"
#endif
}

#define LOG_TAG "Native-FFmpegDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 回调相关变量
static jobject g_decoderListener = nullptr;
static jmethodID g_onFrameDecodedMethod = nullptr;
const int BUFFER_SECS = 2;  // 缓冲秒数
const int MIN_QUEUE_SIZE = 5;  // 最小队列大小
const int MAX_QUEUE_SIZE = 60;  // 最大队列大小

// FFmpeg 相关资源封装
struct FFmpegContext {
    AVFormatContext *formatContext = nullptr;  // 存储音视频封装格式中包含的所有信息
    AVCodecContext *codecContext = nullptr;    // 编解码器上下文，存储编解码器的相关参数
    const AVCodec *codec = nullptr;           // 编解码器，包含实际的编解码功能实现
    double frameRate = 0.0;  // 添加帧率字段
    int64_t frameInterval = 0;  // 帧间隔（微秒）
    int64_t nextFrameTime = 0;  // 下一帧的目标时间
    int targetQueueSize;  // 目标队列大小
    int64_t startTime = 0;        // 开始播放时间
    int64_t totalDuration = 0;    // 视频总时长（微秒）
    int64_t currentTime = 0;      // 当前播放时间（微秒）
    double timeBase = 0.0;        // 时间基准
    
    // 获取格式化的间字符串
    static std::string getFormattedTime(int64_t timeInMicros) {
        int64_t totalSeconds = timeInMicros / AV_TIME_BASE;
        // 确保 totalSeconds 在 int 范围内
        if (totalSeconds > std::numeric_limits<int>::max()) {
            totalSeconds = std::numeric_limits<int>::max();
        } else if (totalSeconds < std::numeric_limits<int>::min()) {
            totalSeconds = std::numeric_limits<int>::min();
        }
        int hours = static_cast<int>(totalSeconds / 3600);
        int minutes = static_cast<int>((totalSeconds % 3600) / 60);
        int seconds = static_cast<int>(totalSeconds % 60);
        char buffer[32];
        snprintf(buffer, sizeof(buffer), "%02d:%02d:%02d", hours, minutes, seconds);
        return {buffer};
    }

    ~FFmpegContext() {
        // 析构函数：确保资源被正确释放
        if (codecContext) {
            avcodec_free_context(&codecContext);
        }
        if (formatContext) {
            avformat_close_input(&formatContext);
        }
    }

    void calculateTargetQueueSize() {
        // 根据帧率计算合适的队列大小
        targetQueueSize = static_cast<int>(frameRate * BUFFER_SECS);
        // 确保队列大小在合理范围内
        targetQueueSize = std::max(MIN_QUEUE_SIZE, 
                          std::min(targetQueueSize, MAX_QUEUE_SIZE));
        LOGI("设置目标队列大小: %d (帧率: %.2f)", targetQueueSize, frameRate);
    }
};

// 全局变量
std::unique_ptr<FFmpegContext> ffmpegContext;           // FFmpeg上下文的智能指针
std::mutex frameQueueMutex;                            // 帧队列互斥锁，用于线程同步
std::condition_variable frameQueueCV;                  // 条件变量，用于线程间通信
std::queue<AVFrame *> frameQueue;                      // 存储解码后等待渲染的帧队列
std::atomic<bool> g_isDecoding(false);                 // 原子变量，控制解码过程


// 工具函数：释放帧资源
inline void freeFrame(AVFrame *frame) {
    if (frame) {
        av_frame_free(&frame);
    }
}

// 初始化解码器函数
extern "C" JNIEXPORT jintArray JNICALL
Java_com_giffard_video_1player_decoder_FFmpegDecoder_initDecoder(JNIEnv *env, jobject thiz,
                                                                 jstring videoPath) {
    const char *path = env->GetStringUTFChars(videoPath, nullptr);
    ffmpegContext = std::make_unique<FFmpegContext>();

    avformat_network_init();
    LOGI("Initializing decoder with video path: %s", path);

    if (avformat_open_input(&ffmpegContext->formatContext, path, nullptr, nullptr) != 0) {
        LOGE("Failed to open video file: %s", path);
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    if (avformat_find_stream_info(ffmpegContext->formatContext, nullptr) < 0) {
        LOGE("Failed to find stream info for: %s", path);
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    int videoStreamIndex = -1;
    for (unsigned int i = 0; i < ffmpegContext->formatContext->nb_streams; i++) {
        if (ffmpegContext->formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = static_cast<int>(i); // Explicitly cast to int
            break;
        }
    }

    if (videoStreamIndex == -1) {
        LOGE("No video stream found");
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    AVStream *videoStream = ffmpegContext->formatContext->streams[videoStreamIndex];
    ffmpegContext->codec = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!ffmpegContext->codec) {
        LOGE("Failed to find codec for video stream");
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    ffmpegContext->codecContext = avcodec_alloc_context3(ffmpegContext->codec);
    if (!ffmpegContext->codecContext) {
        LOGE("Failed to allocate codec context");
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    if (avcodec_parameters_to_context(ffmpegContext->codecContext, videoStream->codecpar) < 0) {
        LOGE("Failed to copy codec parameters");
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    if (avcodec_open2(ffmpegContext->codecContext, ffmpegContext->codec, nullptr) < 0) {
        LOGE("Failed to open codec");
        env->ReleaseStringUTFChars(videoPath, path);
        return nullptr;
    }

    // 获取视频流的帧率并计算队列大小
    ffmpegContext->frameRate = av_q2d(videoStream->avg_frame_rate);
    ffmpegContext->calculateTargetQueueSize();

    ffmpegContext->timeBase = av_q2d(videoStream->time_base);

    jclass decoderClass = env->GetObjectClass(thiz);
    g_onFrameDecodedMethod = env->GetMethodID(decoderClass, "onFrameDecoded",
                                              "(Ljava/nio/ByteBuffer;)V");
    g_decoderListener = env->NewGlobalRef(thiz);

    env->ReleaseStringUTFChars(videoPath, path);
    LOGI("Decoder initialized successfully");

    // 获取视频总时长
    if (ffmpegContext->formatContext->duration != AV_NOPTS_VALUE) {
        ffmpegContext->totalDuration = ffmpegContext->formatContext->duration;
        LOGI("视频总时长: %s", 
             ffmpegContext->getFormattedTime(ffmpegContext->totalDuration).c_str());
    }

    // 返回视频信息数组 [width, height, frameRate]
    jintArray info = env->NewIntArray(3);
    jint fill[3] = {
        ffmpegContext->codecContext->width,
        ffmpegContext->codecContext->height,
        static_cast<jint>(ffmpegContext->frameRate)
    };
    env->SetIntArrayRegion(info, 0, 3, fill);
    return info;
}

// 解码线程函数：负责从视频文件中读取数据并解码
void decodeThreadFunc() {
    // Use av_packet_alloc to allocate a new AVPacket
    AVPacket *packet = av_packet_alloc();
    if (!packet) {
        LOGE("无法分配 AVPacket");
        return;
    }
    AVFrame *frame = av_frame_alloc();
    if (!frame) {
        LOGE("无法分配 AVFrame");
        av_packet_free(&packet);  // Free the packet if frame allocation fails
        return;
    }

    int64_t lastPts = 0;
    while (g_isDecoding) {
        if (av_read_frame(ffmpegContext->formatContext, packet) < 0) {
            break;
        }

        if (packet->stream_index == 0) {
            if (avcodec_send_packet(ffmpegContext->codecContext, packet) == 0) {
                while (avcodec_receive_frame(ffmpegContext->codecContext, frame) == 0) {
                    // 使用 PTS 计算实际播放时间
                    int64_t pts = frame->pts;
                    if (pts != AV_NOPTS_VALUE) {
                        auto timeInMicros = static_cast<int64_t>(pts *
                            ffmpegContext->timeBase * AV_TIME_BASE);
                        
                        // 计算需要等待的时间
                        if (lastPts != 0) {
                            int64_t waitTime = timeInMicros - lastPts;
                            if (waitTime > 0) {
                                av_usleep(static_cast<unsigned int>(waitTime));
                            }
                        }
                        lastPts = timeInMicros;
                    }

                    std::unique_lock<std::mutex> lock(frameQueueMutex);
                    frameQueueCV.wait(lock, [&]() { 
                        return frameQueue.size() < ffmpegContext->targetQueueSize; 
                    });

                    AVFrame *clonedFrame = av_frame_clone(frame);
                    if (clonedFrame) {
                        clonedFrame->pts = pts;  // 保存 PTS
                        frameQueue.push(clonedFrame);
                        frameQueueCV.notify_one();
                    }
                }
            }
        }
        av_packet_unref(packet);  // Unreference the packet after use
    }

    av_frame_free(&frame);
    av_packet_free(&packet);  // Free the packet when done
    LOGI("解码线程结束");
}

// 渲染线程函数：负责将解码后的帧传递给Java层
void renderThreadFunc(JavaVM *jvm) {
    JNIEnv *env = nullptr;
    if (jvm->AttachCurrentThread(&env, nullptr) != 0) {
        LOGE("无法将线程附加到 JVM");
        return;
    }

    int64_t lastRenderTime = av_gettime_relative();
    int64_t renderInterval = ffmpegContext->frameInterval;

    while (g_isDecoding) {
        AVFrame* frame = nullptr;
        {
            std::unique_lock<std::mutex> lock(frameQueueMutex);
            
            if (frameQueueCV.wait_for(lock, std::chrono::microseconds(renderInterval),
                                    []() { return !frameQueue.empty(); })) {
                
                frame = frameQueue.front();
                frameQueue.pop();
                
                // 使用 PTS 更新当前播放时间
                if (frame->pts != AV_NOPTS_VALUE) {
                    ffmpegContext->currentTime = static_cast<int64_t>(frame->pts * 
                        ffmpegContext->timeBase * AV_TIME_BASE);
                    
                    // 每秒输出一次播放进度
                    static int64_t lastLogTime = 0;
                    if (ffmpegContext->currentTime - lastLogTime >= AV_TIME_BASE) {
                        LOGI("播放进度: %s / %s", 
                             ffmpegContext->getFormattedTime(ffmpegContext->currentTime).c_str(),
                             ffmpegContext->getFormattedTime(ffmpegContext->totalDuration).c_str());
                        lastLogTime = ffmpegContext->currentTime;
                    }
                }
            }
        }
        
        if (frame) {
            // 计算渲染时间
            int64_t currentTime = av_gettime_relative();
            int64_t elapsedTime = currentTime - lastRenderTime;
            
            if (elapsedTime < renderInterval) {
                av_usleep(static_cast<unsigned int>(renderInterval - elapsedTime));
            }
            
            // 渲染帧
            if (frame->data[0] && g_decoderListener && g_onFrameDecodedMethod) {
                int bufferSize = av_image_get_buffer_size(
                    static_cast<AVPixelFormat>(frame->format),
                    frame->width, frame->height, 1
                );
                
                if (bufferSize > 0) {
                    // 创建临时缓冲区并复制数据
                    auto* buffer = new uint8_t[bufferSize];
                    av_image_copy_to_buffer(
                        buffer, bufferSize,
                        frame->data, frame->linesize,
                        static_cast<AVPixelFormat>(frame->format),
                        frame->width, frame->height, 1
                    );

                    jobject byteBuffer = env->NewDirectByteBuffer(buffer, bufferSize);
                    if (byteBuffer) {
                        env->CallVoidMethod(g_decoderListener, g_onFrameDecodedMethod, byteBuffer);
                        env->DeleteLocalRef(byteBuffer);
                    }
                    delete[] buffer;
                }
            }
            
            freeFrame(frame);
            lastRenderTime = av_gettime_relative();
        }
    }

    jvm->DetachCurrentThread();
    LOGI("渲染线程结束");
}

// 启动解码和渲染线程
extern "C" JNIEXPORT void JNICALL
Java_com_giffard_video_1player_decoder_FFmpegDecoder_startNativeDecoding(JNIEnv *env,
                                                                         jobject thiz) {
    LOGI("startNativeDecoding");
    if (g_isDecoding) {
        LOGI("Decoding already started.");
        return;
    }

    g_isDecoding = true;
    ffmpegContext->startTime = av_gettime_relative();

    JavaVM *jvm = nullptr;
    if (env->GetJavaVM(&jvm) != 0) {
        LOGE("Failed to get JavaVM");
        return;
    }

    std::thread(decodeThreadFunc).detach();
    std::thread(renderThreadFunc, jvm).detach();
}

// 停止解码和渲染线程
extern "C" JNIEXPORT void JNICALL
Java_com_giffard_video_1player_decoder_FFmpegDecoder_stopNativeDecoding(JNIEnv *env,
                                                                        jobject thiz) {
    LOGI("stopNativeDecoding");

    if (!g_isDecoding) {
        return;
    }

    g_isDecoding = false;
    frameQueueCV.notify_all();

    std::unique_lock<std::mutex> lock(frameQueueMutex);
    while (!frameQueue.empty()) {
        freeFrame(frameQueue.front());
        frameQueue.pop();
    }

    LOGI("Stopped decoding and rendering.");
}

// 释放解码器资源
extern "C" JNIEXPORT void JNICALL
Java_com_giffard_video_1player_decoder_FFmpegDecoder_releaseDecoder(JNIEnv *env, jobject thiz) {
    LOGI("releaseDecoder");

    g_isDecoding = false;
    frameQueueCV.notify_all();
    ffmpegContext.reset();

    if (g_decoderListener) {
        env->DeleteGlobalRef(g_decoderListener);
        g_decoderListener = nullptr;
    }

    LOGI("Decoder released");
}

