#pragma once
#define VK_USE_PLATFORM_ANDROID_KHR
#define VK_NO_PROTOTYPES // all calls go through pointers from the loaded driver (system or Turnip)
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <string>
#include <vector>

#include "libretro.h"
#include "libretro_vulkan.h"
#include "vk_post.h"
#include "vk_look.h"
#include "libra.h"

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
    void resizeSwapchain();                // rebuild at the window's current size
    bool hasWindow() const { return swapchain_ != VK_NULL_HANDLE; }
    // A window without a swapchain (its create failed) or whose surface was lost: the run loop calls retrySwapchain
    // every 250 ms, or for a lost surface attaches the window again (code audit 3, p5 L1). Before, the screen stayed
    // black until the next surface change.
    bool needsSwapchain() const { return surface_ != VK_NULL_HANDLE && swapchain_ == VK_NULL_HANDLE && !lost_; }
    bool surfaceLost() const { return surfaceLost_; }
    void retrySwapchain();
    uint64_t refreshNs() const { return refreshNs_; } // panel refresh period (display timing), 0 = unknown

    // Around retro_run: acquire a swapchain image (= sync index) and present it afterwards.
    // endFrame re-presents the last image if the core produced no new one.
    // show = false (fast-forward frame skip): the core's work is submitted but nothing is presented.
    bool beginFrame(bool show = true);
    void endFrame();
    void videoRefresh(const void* data, unsigned w, unsigned h); // from retro_video_refresh
    // Paused: shows the last picture again (the in-game Picture panel changed how it is drawn).
    void showAgain();
    // Fast-forward: present without vsync. Applied (swapchain rebuilt) at the next beginFrame.
    void setUnthrottled(bool on) { wantUnthrottled_ = on; }
    // The GPU stopped answering: a submit failed, VK_ERROR_DEVICE_LOST, or a frame's work wasn't done in 5 s (code audit
    // 3, M12: the next frame waited for its fence for good, then the main thread's stop for the emulation thread).
    // beginFrame then refuses every frame; the host stops the game.
    bool lost() const { return lost_; }
    // The game's frame rate (60, or 50 for PAL), for the swap interval on fast screens.
    void setContentRate(double fps) { contentHz_ = fps; }
    // Sharp display: scale the core's image up by whole numbers with nearest filtering first, then
    // linearly to the screen (sharp-bilinear), instead of one linear scale. Pixels keep crisp edges
    // without the uneven widths of plain nearest. Can be switched while running.
    void setSharp(bool on) { sharp_ = on; }
    // The last picture the core handed over, at its own size, as top-down RGBA8 (screenshots, state
    // pictures). Between frames on the emulation thread only; waits for the GPU. False if there's none.
    bool capture(std::vector<uint8_t>& rgba, unsigned& w, unsigned& h);

    // Latency (VK_GOOGLE_display_timing, when the driver has it): notePoll() at each input poll;
    // takeLatency() returns the mean and max ms from the last poll before a present to that image
    // reaching the display, over the frames reported since the previous call. False if unknown.
    void notePoll(int64_t ns) { pollNs_ = ns; }
    // uneven: consecutive frames whose display times were not ~16.7 ms apart (judder).
    bool takeLatency(double& meanMs, double& maxMs, int& uneven);
    // Cadence since the last call: how many refreshes each frame stayed on screen (1/2/3/4+; 2 is
    // even at 60 fps on 120 Hz) and the mean/sd of the time between our present calls. "" if unknown.
    std::string takeCadence();

    const retro_hw_render_interface_vulkan* iface() const { return &iface_; }
    void destroy();

private:
    void markLost(const char* what, VkResult r);
    bool waitFrameFence(uint32_t i);
    std::atomic<bool> lost_{false};
    bool createSwapchain();
    void startSwapchain();
    void destroySwapchain();
    void loseSurface(const char* where);
    bool surfaceLost_ = false;
    uint32_t swapFails_ = 0; // failed creates in a row (their log lines are thinned out)
    void present(bool newImage);
    bool acquireImage();
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
    // Default (N64_SKIP_DUPES=0 turns it off): frames start without a swapchain image; present acquires one only for a new frame
    // (or repaint_: the last image must go on a new swapchain), so repeat frames aren't presented.
    bool skipDupes_ = false, needAcquire_ = false, repaint_ = true;
    bool black_ = false; // this present is black frame insertion's black refresh
    // N64_EARLY_ACQUIRE=<n> (default 30 on Mali, set by MainActivity; off elsewhere): with skipDupes_, once n frames in a row were new images the next one acquires
    // at frame start like the plain path, so a blocking acquire waits before the input poll (games that draw every
    // refresh, e.g. Smash on the Flip: poll->display 58 ms skipping vs 40 ms not). A repeat ends the streak.
    int earlyAcquire_ = 0;
    int newStreak_ = 0;
    std::atomic<bool> wantUnthrottled_{false};
    bool unthrottled_ = false;

    // Display timing: poll time per present ID (ring), sums since the last takeLatency().
    PFN_vkGetPastPresentationTimingGOOGLE getPastTiming_ = nullptr;
    // Swap interval: presents per game frame, so each frame stays up the same number of refreshes
    // (2 on a 120 Hz screen). Vulkan has no swap interval; like RetroArch, we present the frame again.
    PFN_vkGetRefreshCycleDurationGOOGLE getRefreshCycle_ = nullptr;
    double contentHz_ = 60.0;
    uint32_t swapInterval_ = 1;
    void updateSwapInterval();
    // N64_SWAP_INTERVAL: 1 = present each frame swapInterval_ times; 2 = timed pacing: present
    // once, asking for a display time (VK_GOOGLE_display_timing) on a fixed grid of every
    // swapInterval_-th refresh, and start each frame just early enough to make its slot.
    int swapMode_ = 0;
    uint64_t refreshNs_ = 0;
    int64_t frameStartNs_ = 0;   // when the current frame's work began (after the pacing sleep)
    double extraLeadNs_ = 0;     // timed pacing: lead added after missed slots (timingMutex_)
    double workMaxNs_ = 12e6;    // decaying peak of frame start -> present call
    uint64_t lastTargetNs_ = 0, desiredNs_ = 0;
    void paceFrame();
    static constexpr uint32_t kTimingRing = 64;
    int64_t pollNs_ = 0, presentPollNs_[kTimingRing] = {};
    uint32_t presentId_ = 0;
    double latSumMs_ = 0, latMaxMs_ = 0;
    uint32_t latCount_ = 0;
    uint64_t lastActual_ = 0; uint32_t lastId_ = 0; int uneven_ = 0;
    int shownFor_[4] = {};
    int64_t lastCallNs_ = 0; double callSum_ = 0, callSq_ = 0; int callN_ = 0;
    void collectTiming();
    std::mutex timingMutex_;

    VkFormat format_ = VK_FORMAT_UNDEFINED;
    // Sharp display: the whole-number prescale target, made (or remade) when the size or format changes.
    std::atomic<bool> sharp_{false};
    VkImage sharpImage_ = VK_NULL_HANDLE;
    VkDeviceMemory sharpMem_ = VK_NULL_HANDLE;
    uint32_t sharpW_ = 0, sharpH_ = 0;
    VkFormat sharpFormat_ = VK_FORMAT_UNDEFINED;
    bool ensureSharpImage(uint32_t w, uint32_t h, VkFormat format);
    void destroySharpImage();
    // Settings › Video's colour and sharpening (vk_post.h), only while one of them is changed.
    VkPost post_;
    // Settings › Video's Shader, Sides of the picture, Overlay and colour-blind help (vk_look.h).
    VkLook look_;
    // Your own RetroArch preset through librashader (libra.h), drawn into libraImage_ at the picture's shown
    // size, then blitted in or drawn by the Picture pass (Sides, Overlay).
    LibraVk libra_;
    VkImage libraImage_ = VK_NULL_HANDLE;
    VkDeviceMemory libraMem_ = VK_NULL_HANDLE;
    VkImageView libraView_ = VK_NULL_HANDLE;
    uint32_t libraW_ = 0, libraH_ = 0;
    bool ensureLibraImage(uint32_t w, uint32_t h);
    void destroyLibraImage();
    // Crop edges: the kept part of the core's picture, copied out each present; everything after reads it.
    VkImage cropImage_ = VK_NULL_HANDLE;
    VkDeviceMemory cropMem_ = VK_NULL_HANDLE;
    VkImageView cropView_ = VK_NULL_HANDLE;
    uint32_t cropW_ = 0, cropH_ = 0;
    VkFormat cropFormat_ = VK_FORMAT_UNDEFINED;
    bool ensureCropImage(uint32_t w, uint32_t h, VkFormat format);
    void destroyCropImage();

    // State handed over by the core for the next video_refresh.
    retro_vulkan_image image_{};
    bool haveImage_ = false;
    unsigned imageW_ = 0, imageH_ = 0;
    uint32_t srcQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> waitSems_;
    std::vector<VkCommandBuffer> coreCmds_;
    VkSemaphore signalSem_ = VK_NULL_HANDLE;
};
