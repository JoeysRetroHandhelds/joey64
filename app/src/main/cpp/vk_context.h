#pragma once
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <mutex>
#include <vector>

#include "libretro.h"
#include "libretro_vulkan.h"

// Vulkan frontend for libretro cores that render with RETRO_HW_CONTEXT_VULKAN (parallel-RDP).
// Owns the instance, surface and swapchain; the device comes from the core's negotiation
// interface when it offers one. Each frame the core's image is blitted (4:3 letterbox) into
// the acquired swapchain image. All calls except setWindow happen on the emulation thread.
class VkContext {
public:
    // Device + queue. negotiation may be null (frontend picks the device).
    bool init(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    void attachWindow(ANativeWindow* win); // nullptr detaches (swapchain + surface destroyed)
    bool hasWindow() const { return swapchain_ != VK_NULL_HANDLE; }

    // Around retro_run: acquire a swapchain image (= sync index) and present it afterwards.
    // endFrame re-presents the last image if the core produced no new one.
    bool beginFrame();
    void endFrame();
    void videoRefresh(const void* data, unsigned w, unsigned h); // from retro_video_refresh

    const retro_hw_render_interface_vulkan* iface() const { return &iface_; }
    void destroy();

private:
    bool createSwapchain();
    void destroySwapchain();
    void present(bool newImage);

    // retro_hw_render_interface_vulkan callbacks (handle = this)
    static void setImage(void*, const retro_vulkan_image*, uint32_t, const VkSemaphore*, uint32_t);
    static uint32_t getSyncIndex(void*);
    static uint32_t getSyncIndexMask(void*);
    static void setCommandBuffers(void*, uint32_t, const VkCommandBuffer*);
    static void waitSyncIndex(void*);
    static void lockQueue(void*);
    static void unlockQueue(void*);
    static void setSignalSemaphore(void*, VkSemaphore);

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
    // Per swapchain image: command buffer, fence, render-done semaphore.
    std::vector<VkCommandBuffer> cmds_;
    std::vector<VkFence> fences_;
    std::vector<VkSemaphore> renderDone_;
    // Acquire semaphores rotate independently of the image index.
    std::vector<VkSemaphore> acquireSems_;
    uint32_t acquireSlot_ = 0;
    VkSemaphore currentAcquire_ = VK_NULL_HANDLE;
    uint32_t index_ = 0;
    bool acquired_ = false, presented_ = false;

    // State handed over by the core for the next video_refresh.
    retro_vulkan_image image_{};
    bool haveImage_ = false;
    unsigned imageW_ = 0, imageH_ = 0;
    uint32_t srcQueueFamily_ = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> waitSems_;
    std::vector<VkCommandBuffer> coreCmds_;
    VkSemaphore signalSem_ = VK_NULL_HANDLE;
};
