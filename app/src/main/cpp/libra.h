#pragma once
// RetroArch shader presets (.slangp) through librashader (MPL-2.0, its own .so loaded at run time, built by
// devtools/build-librashader.sh; the header is MIT). Settings › Video › Shader › Your own RetroArch preset
// (approved mockup 2026-11-08, scratchpad build_libra_mock.py): NativeBridge.setSlang picks the preset and
// its settings; the dev switch `--es env N64_SLANGP=<path>` still wins over it. While one is on, the
// renderers draw Colour first, then the preset, then Sides of the picture and Overlay (Sharpening is off).
#include <vulkan/vulkan.h>
#include <GLES3/gl3.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace Libra {
/** The picked preset, from the UI thread; gen counts new presets, paramGen new settings for the same one. */
struct Setting {
    std::mutex m;
    std::string path;
    std::vector<std::pair<std::string, float>> params;
    std::atomic<uint32_t> gen{0}, paramGen{0};
    // How the newest chain went: "" not tried yet, "ok", or librashader's message; for that path.
    std::string status, statusPath;
};
Setting& setting();

/** The preset in effect (the dev switch, else the setting), or "" for none. */
std::string presetPath();
/** Sets the preset (empty: none) and its settings; a new path rebuilds the chain, new settings don't. */
void set(const std::string& path, std::vector<std::pair<std::string, float>> params);
/** "ok\n<path>", "error\n<path>\n<message>", or "" before the chain for the current preset was tried. */
std::string status();
bool available();

/** A preset's settings: name, description, the preset's own value, minimum, maximum, step. */
struct ParamInfo { std::string name, description; float value, minimum, maximum, step; };
bool params(const char* path, std::vector<ParamInfo>& out, std::string& err);

/**
 * The preset drawn on an rgba picture (top row first, w x h) at outW x outH on an offscreen GLES context of
 * its own, for Settings' previews. The chain is kept for the next call until [previewClose].
 */
bool preview(const char* path, const std::vector<std::pair<std::string, float>>& params, const uint8_t* rgba, int w, int h,
             int outW, int outH, std::vector<uint8_t>& out, std::string& err);
void previewClose();
}

/** The Vulkan runtime: records the chain into the present pass's command buffer. */
class LibraVk {
public:
    /**
     * Makes the chain for the current preset (librashader submits its set-up to [queue], under [queueMutex]),
     * remaking it when the preset changes (the caller waits for the queue first: [stale]). False when none is set or it failed
     * (logged and reported in Libra::status; not tried again until another preset is set).
     */
    bool ready(VkInstance instance, VkPhysicalDevice gpu, VkDevice device, VkQueue queue,
               PFN_vkGetInstanceProcAddr gipa, std::mutex& queueMutex, uint32_t framesInFlight);
    /** [src] in SHADER_READ_ONLY_OPTIMAL; [out] in COLOR_ATTACHMENT_OPTIMAL, left so. */
    void frame(VkCommandBuffer cmd, VkImage src, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
               VkImage out, VkFormat outFormat, uint32_t outW, uint32_t outH);
    void destroy();
    /** A chain for an older preset: [ready] will free it, so the queue must be idle first. */
    bool stale() const;
private:
    void* chain_ = nullptr;
    bool failed_ = false;
    uint32_t gen_ = 0, paramGen_ = 0;
    std::string path_;
    size_t frames_ = 0;
};

/** The OpenGL runtime on the GLES context (GLSL ES 300/310/320, picked from the context). */
class LibraGl {
public:
    bool ready();
    /** Draws [srcTex] (srcW x srcH, GL rows: bottom first) into [outTex] (outW x outH). */
    void frame(GLuint srcTex, int srcW, int srcH, GLuint outTex, int outW, int outH);
    void destroy();
private:
    void* chain_ = nullptr;
    bool failed_ = false;
    uint32_t gen_ = 0, paramGen_ = 0;
    std::string path_;
    size_t frames_ = 0;
};
