package com.giffard.video_player.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoRenderer :
    GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "VideoRenderer"

        // 顶点着色器
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 修改片段着色器，使用更准确的 YUV 到 RGB 转换
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTextureY;
            uniform sampler2D uTextureU;
            uniform sampler2D uTextureV;
            varying vec2 vTexCoord;
            
            void main() {
                // 取 YUV 值
                float y = texture2D(uTextureY, vTexCoord).r;
                float u = texture2D(uTextureU, vTexCoord).r - 0.5;
                float v = texture2D(uTextureV, vTexCoord).r - 0.5;
                
                // BT.601 标准的 YUV 到 RGB 转换
                vec3 rgb;
                rgb.r = y + 1.13983 * v;
                rgb.g = y - 0.39465 * u - 0.58060 * v;
                rgb.b = y + 2.03211 * u;
                
                // 确保颜色在有效范围内
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """
    }

    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var yTextureHandle = 0
    private var uTextureHandle = 0
    private var vTextureHandle = 0

    // YUV 纹理 ID
    private val textureIds = IntArray(3)

    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer

    // 视频尺寸
    private var videoWidth = 0
    private var videoHeight = 0

    // YUV 数据
    private var yuvData: ByteBuffer? = null

    @Volatile
    private var frameAvailable = false

    private var currentBuffer: ByteBuffer? = null
    private val bufferLock = Object()

    init {
        // 修改顶点坐标和纹理坐标
        val vertexData = floatArrayOf(
            -1f, -1f,  // 左下
             1f, -1f,  // 右下
            -1f,  1f,  // 左上
             1f,  1f   // 右上
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        vertexBuffer.position(0)

        // 修改纹理坐标，确保 UV 采样正确
        val texCoordData = floatArrayOf(
            0f, 1f,    // 左下
            1f, 1f,    // 右下
            0f, 0f,    // 左上
            1f, 0f     // 右上
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoordData)
        texCoordBuffer.position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 创建着色器程序
        programId = createProgram()

        // 获取着色器中的变量句柄
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        yTextureHandle = GLES20.glGetUniformLocation(programId, "uTextureY")
        uTextureHandle = GLES20.glGetUniformLocation(programId, "uTextureU")
        vTextureHandle = GLES20.glGetUniformLocation(programId, "uTextureV")

        // 创建纹理
        GLES20.glGenTextures(3, textureIds, 0)
        for (i in 0..2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(bufferLock) {
            if (currentBuffer != null) {
                updateYUVTextures(currentBuffer!!)
            }
        }

        // 清除背景
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // 使用着色器程序
        GLES20.glUseProgram(programId)

        // 禁用深度测试
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        
        // 设置混合模式
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // 设置顶点属性
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 绑定纹理
        for (i in 0..2) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            when (i) {
                0 -> GLES20.glUniform1i(yTextureHandle, i)
                1 -> GLES20.glUniform1i(uTextureHandle, i)
                2 -> GLES20.glUniform1i(vTextureHandle, i)
            }
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理状态
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun updateYUVTextures(data: ByteBuffer) {
        if (videoWidth == 0 || videoHeight == 0) return

        val ySize = videoWidth * videoHeight
        val uvSize = ySize / 4

        // 更新 Y 分量纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        data.position(0)
        data.limit(ySize)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            videoWidth, videoHeight, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            data
        )

        // 更新 U 分量纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[1])
        data.position(ySize)
        data.limit(ySize + uvSize)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            videoWidth / 2, videoHeight / 2, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            data
        )

        // 更新 V 分量纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[2])
        data.position(ySize + uvSize)
        data.limit(ySize + 2 * uvSize)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            videoWidth / 2, videoHeight / 2, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            data
        )

        // 重置 buffer 位置
        data.position(0)
        data.limit(data.capacity())
    }

    private fun createProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Error linking program: $error")
            GLES20.glDeleteProgram(program)
            return 0
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Error compiling shader: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun setVideoDimensions(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    fun setYUVData(data: ByteBuffer) {
        synchronized(bufferLock) {
            // 复制数据到新的 ByteBuffer
            val newBuffer = ByteBuffer.allocateDirect(data.capacity())
            data.rewind()
            newBuffer.put(data)
            newBuffer.rewind()
            currentBuffer = newBuffer
        }
    }
}
