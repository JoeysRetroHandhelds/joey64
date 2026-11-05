# malictr: Mali GPU counter logger for the adb shell (dev tool, not part of the app).
# Needs Arm's libGPUCounters (MIT, https://github.com/ARM-software/libGPUCounters, tag 2.5.1) checked out at HWCPIPE_DIR.
# Build (Git Bash): <sdk>/cmake/3.22.1/bin/cmake -G Ninja -DCMAKE_MAKE_PROGRAM=<sdk>/cmake/3.22.1/bin/ninja
#   -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29
#   -DCMAKE_BUILD_TYPE=Release -DHWCPIPE_DIR=<libGPUCounters> -B build . && ninja -C build
# Run: adb push build/malictr /data/local/tmp/ && adb shell /data/local/tmp/malictr [interval_ms] [seconds] [stop_file]
cmake_minimum_required(VERSION 3.13.5)
project(malictr CXX)
set(CMAKE_CXX_STANDARD 14)
set(HWCPIPE_DIR "C:/GithubProjects/third_party_dl/libGPUCounters" CACHE PATH "libGPUCounters checkout")
add_subdirectory(${HWCPIPE_DIR} hwcpipe-build)
add_executable(malictr malictr.cpp)
target_link_libraries(malictr device hwcpipe)
