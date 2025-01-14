package com.giffard.video_player.decoder

import android.util.Log
import com.giffard.video_player.decoder.VideoDecoder.DecoderListener
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class FFmpegDecoder : VideoDecoder {
    private var decoderListener: DecoderListener? = null
    private var frameBuffer: ByteBuffer? = null // Buffer to store decoded frames
    private val isInitialized = AtomicBoolean(false) // Tracks if the decoder is initialized
    private val isDecoding = AtomicBoolean(false)    // Tracks if decoding is in progress
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    // JNI Method Declarations
    private external fun initDecoder(videoPath: String): IntArray
    private external fun startNativeDecoding()
    private external fun stopNativeDecoding()
    private external fun releaseDecoder()

    override fun init(videoPath: String) {
        if (isInitialized.get()) {
            Log.w(TAG, "Decoder is already initialized.")
            return
        }

        try {
            val videoInfo = initDecoder(videoPath)
            if (videoInfo.size < 3) {
                throw IllegalStateException("Failed to initialize decoder")
            }
            frameWidth = videoInfo[0]
            frameHeight = videoInfo[1]
            val frameRate = videoInfo[2].toFloat()
            
            isInitialized.set(true)
            Log.i(
                TAG,
                "Decoder initialized successfully for path: $videoPath, dimensions: ${frameWidth}x${frameHeight}, fps: $frameRate"
            )
            decoderListener?.onVideoMetadataReady(frameWidth, frameHeight, frameRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing decoder: ${e.message}")
            throw IllegalStateException("Failed to initialize decoder", e)
        }
    }

    override fun startDecoding() {
        if (!isInitialized.get()) {
            Log.e(TAG, "Decoder not initialized. Call init() before starting decoding.")
            return
        }

        if (isDecoding.get()) {
            Log.w(TAG, "Decoding is already in progress.")
            return
        }

        try {
            startNativeDecoding()
            isDecoding.set(true)
            Log.i(TAG, "Started decoding")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting decoding: ${e.message}")
        }
    }

    override fun stopDecoding() {
        if (!isDecoding.get()) {
            Log.w(TAG, "Decoding is not in progress.")
            return
        }

        try {
            stopNativeDecoding()
            isDecoding.set(false)
            Log.i(TAG, "Stopped decoding")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoding: ${e.message}")
        }
    }

    override fun release() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Decoder not initialized. Nothing to release.")
            return
        }

        try {
            releaseDecoder()
            isInitialized.set(false)
            frameBuffer = null // Release buffer
            decoderListener = null // Release listener
            Log.i(TAG, "Decoder resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}")
        }
    }

    override fun setDecoderListener(listener: DecoderListener) {
        decoderListener = listener
    }

    override fun onFrameDecoded(frame: ByteBuffer?) {
        if (decoderListener == null) {
            Log.w(TAG, "No listener set. Decoded frame will not be processed.")
            return
        }

        frame?.let {
            synchronized(this) {
                try {
                    val capacity = frameBuffer?.capacity() ?: 0
                    if (frameBuffer == null || capacity < it.remaining()) {
                        frameBuffer = ByteBuffer.allocateDirect(it.remaining())
                    }
                    frameBuffer?.apply {
                        clear()
                        put(it)
                        flip()
                        decoderListener?.onFrameDecoded(this, frameWidth, frameHeight)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing decoded frame: ${e.message}")
                }
            }
        } ?: Log.w(TAG, "Received null frame from native layer.")
    }

    companion object {
        private const val TAG = "FFmpegDecoder"
    }
}
