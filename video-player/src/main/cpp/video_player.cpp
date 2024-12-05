#include <jni.h>
#include <android/log.h>
#include <queue>
#include <thread>
#include <condition_variable>
#include <mutex>
#include <memory>
#include <atomic>

extern "C" {
#if defined(__arm64__) || defined(__aarch64__)  // 针对 arm64-v8a 架构
#include "ffmpeg/arm64-v8a/include/libavformat/avformat.h"
#include "ffmpeg/arm64-v8a/include/libavcodec/avcodec.h"
#include "ffmpeg/arm64-v8a/include/libavutil/frame.h"
#include "ffmpeg/arm64-v8a/include/libavutil/imgutils.h"
#elif defined(__x86_64__)  // 针对 x86_64 架构
#include "ffmpeg/x86_64/include/libavformat/avformat.h"
#include "ffmpeg/x86_64/include/libavcodec/avcodec.h"
#include "ffmpeg/x86_64/include/libavutil/frame.h"
#include "ffmpeg/x86_64/include/libavutil/imgutils.h"
#else
// 默认使用通用的头文件
#include "ffmpeg/include/libavformat/avformat.h"
#include "ffmpeg/include/libavcodec/avcodec.h"
#include "ffmpeg/include/libavutil/frame.h"
#include "ffmpeg/include/libavutil/imgutils.h"
#endif
}

#define LOG_TAG "Native-FFmpegDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 回调相关变量
static jobject g_decoderListener = nullptr;
static jmethodID g_onFrameDecodedMethod = nullptr;

// FFmpeg 相关资源封装
struct FFmpegContext {
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    const AVCodec *codec = nullptr;

    ~FFmpegContext() {
        if (codecContext) {
            avcodec_free_context(&codecContext);
        }
        if (formatContext) {
            avformat_close_input(&formatContext);
        }
    }
};

// 全局变量
std::unique_ptr<FFmpegContext> ffmpegContext;
std::mutex frameQueueMutex;
std::condition_variable frameQueueCV;
std::queue<AVFrame *> frameQueue;
std::atomic<bool> g_isDecoding(false);
const int MAX_QUEUE_SIZE = 300;

// 工具函数：释放帧资源
inline void freeFrame(AVFrame *frame) {
    if (frame) {
        av_frame_free(&frame);
    }
}

// 初始化解码器函数
extern "C" JNIEXPORT void JNICALL
Java_com_giffard_video_1player_decoder_FFmpegDecoder_initDecoder(JNIEnv *env, jobject thiz,
                                                                 jstring videoPath) {
    const char *path = env->GetStringUTFChars(videoPath, nullptr);
    ffmpegContext = std::make_unique<FFmpegContext>();

    avformat_network_init();
    LOGI("Initializing decoder with video path: %s", path);

    if (avformat_open_input(&ffmpegContext->formatContext, path, nullptr, nullptr) != 0) {
        LOGE("Failed to open video file: %s", path);
        env->ReleaseStringUTFChars(videoPath, path);
        return;
    }

    if (avformat_find_stream_info(ffmpegContext->formatContext, nullptr) < 0) {
        LOGE("Failed to find stream info for: %s", path);
        env->ReleaseStringUTFChars(videoPath, path);
        return;
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
        return;
    }

    AVStream *videoStream = ffmpegContext->formatContext->streams[videoStreamIndex];
    ffmpegContext->codec = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!ffmpegContext->codec) {
        LOGE("Failed to find codec for video stream");
        env->ReleaseStringUTFChars(videoPath, path);
        return;
    }

    ffmpegContext->codecContext = avcodec_alloc_context3(ffmpegContext->codec);
    if (!ffmpegContext->codecContext) {
        LOGE("Failed to allocate codec context");
        env->ReleaseStringUTFChars(videoPath, path);
        return;
    }

    if (avcodec_parameters_to_context(ffmpegContext->codecContext, videoStream->codecpar) < 0) {
        LOGE("Failed to copy codec parameters");
        env->ReleaseStringUTFChars(videoPath, path);
        return;
    }

    if (avcodec_open2(ffmpegContext->codecContext, ffmpegContext->codec, nullptr) < 0) {
        LOGE("Failed to open codec");
        env->ReleaseStringUTFChars(videoPath, path);
        return;
    }

    jclass decoderClass = env->GetObjectClass(thiz);
    g_onFrameDecodedMethod = env->GetMethodID(decoderClass, "onFrameDecoded",
                                              "(Ljava/nio/ByteBuffer;)V");
    g_decoderListener = env->NewGlobalRef(thiz);

    env->ReleaseStringUTFChars(videoPath, path);
    LOGI("Decoder initialized successfully");
}

// 解码线程
void decodeThreadFunc() {
    AVPacket packet;
    AVFrame *frame = av_frame_alloc();
    if (!frame) {
        LOGE("Failed to allocate AVFrame");
        return;
    }

    while (g_isDecoding && av_read_frame(ffmpegContext->formatContext, &packet) >= 0) {
        if (packet.stream_index == 0) {
            if (avcodec_send_packet(ffmpegContext->codecContext, &packet) == 0) {
                while (avcodec_receive_frame(ffmpegContext->codecContext, frame) == 0) {
                    std::unique_lock<std::mutex> lock(frameQueueMutex);
                    frameQueueCV.wait(lock, []() { return frameQueue.size() < MAX_QUEUE_SIZE; });
                    AVFrame *clonedFrame = av_frame_clone(frame);
                    if (clonedFrame) {
                        LOGI("Decoding thread send frame");
                        frameQueue.push(clonedFrame);
                        frameQueueCV.notify_all();
                    }
                }
            }
        }
        av_packet_unref(&packet);
    }

    freeFrame(frame);
    LOGI("Decoding thread finished");
}

// 渲染线程
void renderThreadFunc(JavaVM *jvm) {
    JNIEnv *env = nullptr;
    if (jvm->AttachCurrentThread(&env, nullptr) != 0) {
        LOGE("Failed to attach thread to JVM");
        return;
    }

    // 检查全局引用和回调方法是否有效
    if (!g_decoderListener || !g_onFrameDecodedMethod) {
        LOGE("Invalid decoder listener or method ID");
        jvm->DetachCurrentThread();
        return;
    }

    while (g_isDecoding) {
        std::unique_lock<std::mutex> lock(frameQueueMutex);
        frameQueueCV.wait(lock, []() { return !frameQueue.empty(); });

        if (!frameQueue.empty()) {
            AVFrame *frame = frameQueue.front();
            frameQueue.pop();
            lock.unlock();

            LOGI("Render thread received frame");

            int bufferSize = av_image_get_buffer_size(static_cast<AVPixelFormat>(frame->format),
                                                      frame->width, frame->height, 1);
            jobject byteBuffer = env->NewDirectByteBuffer(frame->data[0], bufferSize);
            if (byteBuffer) {
                env->CallVoidMethod(g_decoderListener, g_onFrameDecodedMethod, byteBuffer);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    LOGE("Java callback method threw an exception");
                }
                LOGI("Render thread: called onFrameDecodedMethod");
                env->DeleteLocalRef(byteBuffer);
            }

            freeFrame(frame);
        }
    }

    jvm->DetachCurrentThread();
    LOGI("Render thread finished");
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
