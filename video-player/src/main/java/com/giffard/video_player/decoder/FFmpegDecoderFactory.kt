package com.giffard.video_player.decoder

class FFmpegDecoderFactory() : VideoDecoderFactory {
    override fun createDecoder(): VideoDecoder {
        return FFmpegDecoder()
    }
}
