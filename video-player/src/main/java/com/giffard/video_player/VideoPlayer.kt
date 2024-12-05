package com.giffard.video_player


import android.util.Log
import com.giffard.video_player.decoder.VideoDecoder
import com.giffard.video_player.decoder.VideoDecoderFactory
import com.giffard.video_player.renderer.VideoRenderer
import java.nio.ByteBuffer


class VideoPlayer(
    private val videoRenderer: VideoRenderer,
    videoDecoderFactory: VideoDecoderFactory
) : VideoDecoder.DecoderListener {

    private var decoder: VideoDecoder? = null

    companion object {
        const val TAG = "VideoPlayer"

        init {
            Log.i(TAG, "init video_player lib")
            System.loadLibrary("video_player")
        }
    }

    init {
        decoder = videoDecoderFactory.createDecoder()
        decoder?.setDecoderListener(this)
    }

    fun start(videoPath: String) {
        // 初始化并开始解码
        decoder?.init(videoPath)
        decoder?.startDecoding()
    }

    fun stop() {
        decoder?.stopDecoding()
    }

    fun release() {
        decoder?.release()
    }

    // 接收解码后的帧数据，并传递给 VideoRenderer
    override fun onFrameDecoded(yuvBuffer: ByteBuffer) {
        // 将解码后的数据传递给 VideoRenderer
        videoRenderer.setYUVData(yuvBuffer)
    }

    override fun onError(error: String) {

    }
}
