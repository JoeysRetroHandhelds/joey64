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
    rewind_test.cpp
    rewind.cpp
    achievements.cpp
    netplay_server.cpp
    upnp.cpp)

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

# miniupnpc 2.3.3 (BSD-3-Clause; third_party/miniupnpc/README.joey64): opens a netplay room's port on the router.
set(MU third_party/miniupnpc)
add_library(miniupnpc STATIC
    ${MU}/src/igd_desc_parse.c ${MU}/src/miniupnpc.c ${MU}/src/minixml.c ${MU}/src/minisoap.c
    ${MU}/src/minissdpc.c ${MU}/src/miniwget.c ${MU}/src/upnpcommands.c ${MU}/src/upnpdev.c
    ${MU}/src/upnpreplyparse.c ${MU}/src/upnperrors.c ${MU}/src/connecthostport.c
    ${MU}/src/portlistingparse.c ${MU}/src/receivedata.c ${MU}/src/addr_is_reserved.c)
target_include_directories(miniupnpc PUBLIC ${MU}/include PRIVATE ${MU}/src)
target_compile_definitions(miniupnpc PUBLIC MINIUPNP_STATICLIB
    PRIVATE MINIUPNPC_SET_SOCKET_TIMEOUT MINIUPNPC_GET_SRC_ADDR _BSD_SOURCE _DEFAULT_SOURCE _XOPEN_SOURCE=600)
target_compile_options(miniupnpc PRIVATE -O2 -w)

target_include_directories(joey64host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(joey64host android log EGL GLESv3 aaudio dl adrenotools rcheevos miniupnpc z)
# Optimized even in debug builds (the only kind so far): at -O0 the host's audio resampler and
# ring took 4-7% of all CPU on the Retroid Flip.
target_compile_options(joey64host PRIVATE -O2)
