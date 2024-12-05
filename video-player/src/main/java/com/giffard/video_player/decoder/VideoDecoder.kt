package com.giffard.video_player.decoder

import java.nio.ByteBuffer

interface VideoDecoder {
    fun init(videoPath: String)
    fun startDecoding()
    fun onFrameDecoded(frame: ByteBuffer?)
    fun stopDecoding()
    fun release()

    interface DecoderListener {
        fun onFrameDecoded(frame: ByteBuffer, width: Int, height: Int)
        fun onVideoMetadataReady(width: Int, height: Int, frameRate: Float)
        fun onError(error: String)
    }

    fun setDecoderListener(listener: DecoderListener)
}
