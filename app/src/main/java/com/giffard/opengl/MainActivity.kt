package com.giffard.opengl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.coroutineScope
import com.giffard.opengl.databinding.ActivityMainBinding
import com.giffard.video_player.VideoPlayer
import com.giffard.video_player.decoder.FFmpegDecoderFactory
import com.giffard.video_player.renderer.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var videoPlayer: VideoPlayer? = null

    private val videoName = "test1.mp4"
    private var videoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlayer()
        binding.playButton.setOnClickListener {
            if (checkPermissions()) {
                queryAndPlayVideo()
            } else {
                requestPermissions()
            }
        }
    }

    private fun initPlayer() {
        if (checkPermissions()) {
            setupPlayer()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // API < 23 时不需要运行时权限
        }
    }

    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            queryAndPlayVideo()
        } else {
            Toast.makeText(this, "需要存储权限以访问视频文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryAndPlayVideo() {
        videoPath = AppUtil.getVideoUriPath(this, videoName)
        if (videoPath.isNullOrEmpty()) {
            Toast.makeText(this, "未找到视频：$videoName", Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "视频路径: $videoPath")
            playVideo(videoPath!!)
        }
    }

    private fun setupPlayer() {
        if (videoPlayer == null) {
            val renderer = VideoRenderer()
            videoPlayer = VideoPlayer(renderer, FFmpegDecoderFactory())
            binding.glSurfaceView.setEGLContextClientVersion(2)
            binding.glSurfaceView.setRenderer(renderer)
        }
    }

    private fun playVideo(videoPath: String) {
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            videoPlayer?.start(videoPath)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoPlayer?.release()
    }
}
