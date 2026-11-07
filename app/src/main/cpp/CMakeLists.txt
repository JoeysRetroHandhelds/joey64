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
    joeylog.cpp
    vk_context.cpp
    vk_post.cpp
    achievements.cpp)

add_subdirectory(third_party/libadrenotools)

# rcheevos v12.5.0 (MIT, RetroAchievements; copied from RAOfflineProxy's third_party/rcheevos, commit
# 1433173). Without its hashing code (rhash) except md5.c: the host hashes the ROM it already holds.
set(RC third_party/rcheevos)
file(GLOB RC_SOURCES ${RC}/src/*.c ${RC}/src/rapi/*.c ${RC}/src/rcheevos/*.c)
list(FILTER RC_SOURCES EXCLUDE REGEX "rc_client_external\.c$")
add_library(rcheevos STATIC ${RC_SOURCES} ${RC}/src/rhash/md5.c)
target_include_directories(rcheevos PUBLIC ${RC}/include ${RC}/src)
target_compile_definitions(rcheevos PUBLIC RC_DISABLE_LUA)
target_compile_options(rcheevos PRIVATE -O2 -w)

target_include_directories(joey64host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(joey64host android log EGL GLESv3 aaudio dl adrenotools rcheevos)
# Optimized even in debug builds (the only kind so far): at -O0 the host's audio resampler and
# ring took 4-7% of all CPU on the Retroid Flip.
target_compile_options(joey64host PRIVATE -O2)
