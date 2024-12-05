package com.giffard.video_player.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import javax.microedition.khronos.opengles.GL10

class VideoRenderer(private val renderWidth: Int = 0, private val renderHeight: Int = 0) :
    GLSurfaceView.Renderer {

    private var textureId: Int = 0
    private var width = 0
    private var height = 0

    // 用于存储 YUV 数据的缓冲区
    private var yuvBuffer: ByteBuffer? = null

    // 通过解码器获取的视频原始宽高
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    // 控制渲染刷新频率的变量
    private var lastFrameTime = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        textureId = createTexture()
    }

    override fun onDrawFrame(gl: GL10?) {
        // 计算渲染帧率，控制渲染刷新频率
        val currentTime = System.nanoTime()
        val timeDelta = currentTime - lastFrameTime
        val frameInterval = 1_000_000_000 / 30  // 目标帧率30FPS

        if (timeDelta < frameInterval) {
            return // 控制帧率，不需要渲染
        }

        lastFrameTime = currentTime

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 如果有YUV数据，就更新纹理并渲染
        yuvBuffer?.let { buffer ->
            updateTextureWithYUVData(buffer)
            renderFrame()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    fun onSurfaceDestroyed(gl: GL10?) {}

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        return textures[0]
    }

    // 更新纹理数据并执行 YUV 到 RGB 的转换
    private fun updateTextureWithYUVData(yuvBuffer: ByteBuffer) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 获取视频宽高
        val videoWidth = this.videoWidth
        val videoHeight = this.videoHeight

        // 计算 YUV 数据的尺寸
        val ySize = videoWidth * videoHeight
        val uSize = ySize / 4

        val yData = yuvBuffer.array().copyOfRange(0, ySize)
        val uData = yuvBuffer.array().copyOfRange(ySize, ySize + uSize)
        val vData = yuvBuffer.array().copyOfRange(ySize + uSize, ySize + uSize + uSize)

        // 创建 YUV 到 RGB 的着色器和转换逻辑
        val textureData = yuvToRgbConversion(yData, uData, vData, videoWidth, videoHeight)

        // 使用转换后的数据更新纹理
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            videoWidth,
            videoHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            textureData
        )
    }

    // 渲染纹理到屏幕，使用用户指定的渲染宽高
    private fun renderFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 如果没有指定渲染宽高，则使用视频的宽高
        val finalWidth = if (renderWidth == 0) videoWidth else renderWidth
        val finalHeight = if (renderHeight == 0) videoHeight else renderHeight

        // 绘制矩形和纹理
        // 使用合适的着色器代码来渲染纹理
    }

    // 将 YUV 数据转换为 RGB 格式（此处简化为伪代码）
    private fun yuvToRgbConversion(
        yData: ByteArray,
        uData: ByteArray,
        vData: ByteArray,
        width: Int,
        height: Int
    ): ByteBuffer {
        val rgbData = ByteArray(width * height * 4)  // 每个像素 4 字节 RGBA
        var idx = 0

        for (i in 0 until width * height) {
            val y = yData[i].toInt() and 0xFF
            val u = uData[i / 4].toInt() and 0xFF
            val v = vData[i / 4].toInt() and 0xFF

            val r = (y + (1.402 * (v - 128))).toInt()
            val g = (y - (0.344136 * (u - 128)) - (0.714136 * (v - 128))).toInt()
            val b = (y + (1.772 * (u - 128))).toInt()

            // Clip to valid color range (0-255)
            rgbData[idx++] = r.coerceIn(0, 255).toByte()
            rgbData[idx++] = g.coerceIn(0, 255).toByte()
            rgbData[idx++] = b.coerceIn(0, 255).toByte()
            rgbData[idx++] = 255.toByte() // Alpha channel (full opacity)
        }

        return ByteBuffer.wrap(rgbData)
    }

    // 设置视频解码器获取的视频宽高
    fun setVideoDimensions(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
    }

    // 设置 YUV 数据
    fun setYUVData(yuvData: ByteBuffer) {
        this.yuvBuffer = yuvData
    }
}
