cmake_minimum_required(VERSION 3.22.1)

project("video_player")

message( "CMAKE_SOURCE_DIR: ${CMAKE_SOURCE_DIR}")
get_filename_component(PARENT_DIR ${CMAKE_SOURCE_DIR} DIRECTORY)

# 设置 FFmpeg 动态库路径
set(FFMPEG_LIB_DIR ${PARENT_DIR}/jniLibs/${ANDROID_ABI})

# 设置生成的so动态库最后输出的路径
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${PARENT_DIR}/jniLibs/${ANDROID_ABI})

# 创建共享库
add_library(${CMAKE_PROJECT_NAME} SHARED
        video_player.cpp)

# 设置 FFmpeg 动态库
add_library(avutil SHARED IMPORTED)
add_library(avformat SHARED IMPORTED)
add_library(avcodec SHARED IMPORTED)
add_library(swscale SHARED IMPORTED)

# 设置这些库的路径
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)

# 设置头文件目录
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_SOURCE_DIR}/include
        ${CMAKE_SOURCE_DIR}/ffmpeg//${ANDROID_ABI}/include  # 添加 FFmpeg 的头文件目录
)

# 链接 FFmpeg 库和 Android 系统库
target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        avutil
        avformat
        avcodec
        swscale
        android
        log
        atomic
        m
)

message( " video_player library end: ")