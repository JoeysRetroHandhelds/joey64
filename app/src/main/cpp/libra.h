#pragma once
// RetroArch shader presets (.slangp) through librashader (MPL-2.0 or GPL-3.0, its own .so, loaded at run
// time; the header is MIT). Test stage (Joey 2026-11-08, "Yes, download it"): a dev switch only,
// `--es env N64_SLANGP=<path to a .slangp on the device>`, which replaces Settings › Video's Picture pass.
// Settings' "Your own RetroArch preset" row waits for the results on both renderers.
#include <vulkan/vulkan.h>
#include <GLES3/gl3.h>
#include <cstdint>
#include <mutex>

namespace Libra {
/** The preset the dev switch names, or null. */
const char* presetPath();
}

/** The Vulkan runtime: records the chain into the present pass's command buffer. */
class LibraVk {
public:
    /**
     * Makes the chain the first time (librashader submits its set-up to [queue], under [queueMutex]).
     * False when the switch is off or the chain failed (logged once, then never tried again).
     */
    bool ready(VkInstance instance, VkPhysicalDevice gpu, VkDevice device, VkQueue queue,
               PFN_vkGetInstanceProcAddr gipa, std::mutex& queueMutex, uint32_t framesInFlight);
    /** [src] in SHADER_READ_ONLY_OPTIMAL; [out] in COLOR_ATTACHMENT_OPTIMAL, left so. */
    void frame(VkCommandBuffer cmd, VkImage src, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
               VkImage out, VkFormat outFormat, uint32_t outW, uint32_t outH);
    void destroy();
private:
    void* chain_ = nullptr;
    bool failed_ = false;
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
    size_t frames_ = 0;
};
