package com.giffard.video_player.decoder

interface VideoDecoderFactory {
    fun createDecoder(): VideoDecoder
}
