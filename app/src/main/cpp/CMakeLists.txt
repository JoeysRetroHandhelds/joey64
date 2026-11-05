cmake_minimum_required(VERSION 3.22)
project(joey64host C CXX)

add_library(joey64host SHARED
    libretro_host.cpp
    egl_context.cpp
    audio_out.cpp
    perf_hint.cpp
    gpu_clock_pin.cpp
    gpu_counters.cpp
    thermal.cpp
    vk_context.cpp)

add_subdirectory(third_party/libadrenotools)

target_include_directories(joey64host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(joey64host android log EGL GLESv3 aaudio dl adrenotools)
# Optimized even in debug builds (the only kind so far): at -O0 the host's audio resampler and
# ring took 4-7% of all CPU on the Retroid Flip.
target_compile_options(joey64host PRIVATE -O2)
