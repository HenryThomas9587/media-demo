package com.giffard.video_player

import java.nio.ByteBuffer

interface FrameCallback {
    fun onFrameAvailable(frame: ByteBuffer, width: Int, height: Int)
}
