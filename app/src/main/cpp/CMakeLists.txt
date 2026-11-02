cmake_minimum_required(VERSION 3.22)
project(n64host CXX)

add_library(n64host SHARED
    libretro_host.cpp
    egl_context.cpp
    audio_out.cpp)

target_link_libraries(n64host android log EGL GLESv3 aaudio dl)
