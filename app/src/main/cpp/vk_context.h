#pragma once
#define VK_USE_PLATFORM_ANDROID_KHR
#define VK_NO_PROTOTYPES // all calls go through pointers from the loaded driver (system or Turnip)
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <mutex>
#include <string>
#include <vector>

#include "libretro.h"
#include "libretro_vulkan.h"

// Vulkan frontend for libretro cores that render with RETRO_HW_CONTEXT_VULKAN (parallel-RDP).
// Owns the instance, surface and swapchain; the device comes from the core's negotiation
// interface when it offers one. Each frame the core's image is blitted (4:3 letterbox) into
// the acquired swapchain image. All calls except setWindow happen on the emulation thread.
class VkContext {
public:
    // Optional custom driver (e.g. Turnip) loaded through libadrenotools. Call before init.
    // Dirs end with '/'. Falls back to the system driver if loading fails.
    static void setCustomDriver(const char* driverDir, const char* libName, const char* hookDir,
                                const char* tmpDir);
    // Before init: create a throwaway instance with the system or the custom driver and report the
    // first GPU as "vendorID|apiMajor.minor|deviceName", or "" if there is none (a custom driver
    // that fails here is skipped by init, which then uses the system driver).
    static std::string probe(bool custom);

    // Device + queue. negotiation may be null (frontend picks the device).
    bool init(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    void attachWindow(ANativeWindow* win); // nullptr detaches (swapchain + surface destroyed)
    bool hasWindow() const { return swapchain_ != VK_NULL_HANDLE; }

    // Around retro_run: acquire a swapchain image (= sync index) and present it afterwards.
    // endFrame re-presents the last image if the core produced no new one.
    // show = false (fast-forward frame skip): the core's work is submitted but nothing is presented.
    bool beginFrame(bool show = true);
    void endFrame();
    void videoRefresh(const void* data, unsigned w, unsigned h); // from retro_video_refresh
    // Fast-forward: present without vsync. Applied (swapchain rebuilt) at the next beginFrame.
    void setUnthrottled(bool on) { wantUnthrottled_ = on; }

    // Latency (VK_GOOGLE_display_timing, when the driver has it): notePoll() at each input poll;
    // takeLatency() returns the mean and max ms from the last poll before a present to that image
    // reaching the display, over the frames reported since the previous call. False if unknown.
    void notePoll(int64_t ns) { pollNs_ = ns; }
    bool takeLatency(double& meanMs, double& maxMs);

    const retro_hw_render_interface_vulkan* iface() const { return &iface_; }
    void destroy();

private:
    bool createSwapchain();
    void destroySwapchain();
    void present(bool newImage);
    void submitSkipped(bool newImage);

    // retro_hw_render_interface_vulkan callbacks (handle = this)
    static void setImage(void*, const retro_vulkan_image*, uint32_t, const VkSemaphore*, uint32_t);
    static uint32_t getSyncIndex(void*);
    static uint32_t getSyncIndexMask(void*);
    static void setCommandBuffers(void*, uint32_t, const VkCommandBuffer*);
    static void waitSyncIndex(void*);
    static void lockQueue(void*);
    static void unlockQueue(void*);
    static void setSignalSemaphore(void*, VkSemaphore);

    bool loadLibrary();
    void* lib_ = nullptr;
    PFN_vkGetInstanceProcAddr gipa_ = nullptr;
    retro_hw_render_interface_vulkan iface_{};
    const retro_hw_render_context_negotiation_interface_vulkan* negotiation_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    bool ownDevice_ = false;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    std::mutex queueMutex_;
    VkCommandPool pool_ = VK_NULL_HANDLE;

    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    std::vector<VkImage> images_;
    // Frames in flight = the core's sync indices. Fixed for the device's lifetime, since the core
    // sizes per-frame resources from the mask: swapchain rebuilds (fast-forward switches to
    // mailbox, which can return a different image count) must not change it.
    static constexpr uint32_t kSyncFrames = 3;
    VkCommandBuffer frameCmds_[kSyncFrames] = {};
    VkFence frameFences_[kSyncFrames] = {};
    uint32_t sync_ = 0;
    // Per swapchain image: render-done semaphore (waited on by present).
    std::vector<VkSemaphore> renderDone_;
    // Acquire semaphores rotate independently of the image index.
    std::vector<VkSemaphore> acquireSems_;
    uint32_t acquireSlot_ = 0;
    VkSemaphore currentAcquire_ = VK_NULL_HANDLE;
    uint32_t index_ = 0;
    bool acquired_ = false, presented_ = false, skip_ = false;
    bool wantUnthrottled_ = false, unthrottled_ = false;

    // Display timing: poll time per present ID (ring), sums since the last takeLatency().
    PFN_vkGetPastPresentationTimingGOOGLE getPastTiming_ = nullptr;
    static constexpr uint32_t kTimingRing = 64;
    int64_t pollNs_ = 0, presentPollNs_[kTimingRing] = {};
    uint32_t presentId_ = 0;
    double latSumMs_ = 0, latMaxMs_ = 0;
    uint32_t latCount_ = 0;
    void collectTiming();

    // State handed over by the core for the next video_refresh.
    retro_vulkan_image image_{};
    bool haveImage_ = false;
    unsigned imageW_ = 0, imageH_ = 0;
    uint32_t srcQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> waitSems_;
    std::vector<VkCommandBuffer> coreCmds_;
    VkSemaphore signalSem_ = VK_NULL_HANDLE;
};
