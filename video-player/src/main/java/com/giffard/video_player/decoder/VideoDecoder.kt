package com.giffard.video_player.decoder

import java.nio.ByteBuffer

interface VideoDecoder {
    fun init(videoPath: String)
    fun startDecoding()
    fun onFrameDecoded(frame: ByteBuffer?)
    fun stopDecoding()
    fun release()

    interface DecoderListener {
        fun onFrameDecoded(yuvBuffer: ByteBuffer)
        fun onError(error: String)
    }

    fun setDecoderListener(listener: DecoderListener)
}
