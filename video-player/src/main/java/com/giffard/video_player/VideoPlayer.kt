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
        decoder?.init(videoPath)
        decoder?.startDecoding()
    }

    fun stop() {
        decoder?.stopDecoding()
    }

    fun release() {
        decoder?.release()
    }

    override fun onFrameDecoded(frame: ByteBuffer, width: Int, height: Int) {
        videoRenderer.setYUVData(frame)
    }

    override fun onVideoMetadataReady(width: Int, height: Int, frameRate: Float) {
        videoRenderer.setVideoDimensions(width, height)
    }

    override fun onError(error: String) {
        Log.e(TAG, "Decoder error: $error")
    }
}
