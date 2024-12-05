package com.giffard.opengl

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object AppUtil {

    fun getVideoUriPath(context: Context, videoName: String): String? {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.Video.Media._ID) // Android 10+ 使用 ID 获取 URI
        } else {
            arrayOf(MediaStore.Video.Media.DATA) // Android 9- 使用绝对路径
        }

        // 查询条件
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(videoName)

        val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val cursor = context.contentResolver.query(
            queryUri,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (cursor.moveToFirst()) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val id = cursor.getLong(idColumn)
                    ContentUris.withAppendedId(queryUri, id).toString()
                } else {
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    cursor.getString(dataColumn)
                }
            }
        }

        return null // 未找到视频
    }
}
