#include "vk_context.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "n64host", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "n64host", __VA_ARGS__)

namespace {
bool hasExt(const std::vector<VkExtensionProperties>& exts, const char* name) {
    for (auto& e : exts) if (!strcmp(e.extensionName, name)) return true;
    return false;
}

void imageBarrier(VkCommandBuffer cmd, VkImage image, const VkImageSubresourceRange& range,
                  VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                  VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
                  uint32_t srcFamily = VK_QUEUE_FAMILY_IGNORED, uint32_t dstFamily = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess; b.dstAccessMask = dstAccess;
    b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = srcFamily; b.dstQueueFamilyIndex = dstFamily;
    b.image = image; b.subresourceRange = range;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

const VkImageSubresourceRange kColorRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
} // namespace

bool VkContext::init(const retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    negotiation_ = negotiation;

    VkApplicationInfo defaultApp{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    defaultApp.pApplicationName = "N64 Handheld";
    defaultApp.apiVersion = VK_API_VERSION_1_1;
    const VkApplicationInfo* app = &defaultApp;
    if (negotiation_ && negotiation_->get_application_info && negotiation_->get_application_info())
        app = negotiation_->get_application_info();

    const char* instExts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = app;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = instExts;
    if (vkCreateInstance(&ici, nullptr, &instance_) != VK_SUCCESS) { LOGE("vkCreateInstance failed"); return false; }

    // Let the core create the device (Granite knows which extensions/features parallel-RDP wants).
    if (negotiation_ && negotiation_->create_device) {
        retro_vulkan_context ctx{};
        const char* devExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkPhysicalDeviceFeatures noFeatures{}; // Granite dereferences this, so never pass null
        if (negotiation_->create_device(&ctx, instance_, VK_NULL_HANDLE, VK_NULL_HANDLE, vkGetInstanceProcAddr,
                                        devExts, 1, nullptr, 0, &noFeatures)) {
            gpu_ = ctx.gpu; device_ = ctx.device; queue_ = ctx.queue; queueFamily_ = ctx.queue_family_index;
            LOGI("vulkan: device created by core");
        } else {
            LOGE("vulkan: core create_device failed, falling back");
        }
    }

    if (!device_) {
        uint32_t n = 0;
        vkEnumeratePhysicalDevices(instance_, &n, nullptr);
        if (!n) { LOGE("no Vulkan GPU"); return false; }
        std::vector<VkPhysicalDevice> gpus(n);
        vkEnumeratePhysicalDevices(instance_, &n, gpus.data());
        gpu_ = gpus[0];

        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qfs(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &qn, qfs.data());
        queueFamily_ = UINT32_MAX;
        for (uint32_t i = 0; i < qn; ++i)
            if ((qfs[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { queueFamily_ = i; break; }
        if (queueFamily_ == UINT32_MAX) { LOGE("no graphics+compute queue"); return false; }

        uint32_t en = 0;
        vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &en, nullptr);
        std::vector<VkExtensionProperties> avail(en);
        vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &en, avail.data());
        std::vector<const char*> exts = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        for (const char* e : {"VK_KHR_8bit_storage", "VK_KHR_16bit_storage", "VK_KHR_shader_float16_int8",
                              "VK_EXT_external_memory_host", "VK_KHR_driver_properties",
                              "VK_KHR_timeline_semaphore", "VK_KHR_synchronization2"})
            if (hasExt(avail, e)) exts.push_back(e);

        // Enable everything the GPU supports; the core picks what it uses.
        VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
        VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(gpu_, &props);
        if (props.apiVersion >= VK_API_VERSION_1_2) { f2.pNext = &f11; f11.pNext = &f12; }
        vkGetPhysicalDeviceFeatures2(gpu_, &f2);

        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = queueFamily_; qci.queueCount = 1; qci.pQueuePriorities = &prio;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.pNext = &f2;
        dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = (uint32_t)exts.size(); dci.ppEnabledExtensionNames = exts.data();
        if (vkCreateDevice(gpu_, &dci, nullptr, &device_) != VK_SUCCESS) { LOGE("vkCreateDevice failed"); return false; }
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        ownDevice_ = true;
    }

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(gpu_, &props);
    LOGI("vulkan: %s, api %u.%u.%u, driver 0x%x", props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion), props.driverVersion);

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    vkCreateCommandPool(device_, &pci, nullptr, &pool_);

    iface_.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface_.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface_.handle = this;
    iface_.instance = instance_;
    iface_.gpu = gpu_;
    iface_.device = device_;
    iface_.get_device_proc_addr = vkGetDeviceProcAddr;
    iface_.get_instance_proc_addr = vkGetInstanceProcAddr;
    iface_.queue = queue_;
    iface_.queue_index = queueFamily_;
    iface_.set_image = setImage;
    iface_.get_sync_index = getSyncIndex;
    iface_.get_sync_index_mask = getSyncIndexMask;
    iface_.set_command_buffers = setCommandBuffers;
    iface_.wait_sync_index = waitSyncIndex;
    iface_.lock_queue = lockQueue;
    iface_.unlock_queue = unlockQueue;
    iface_.set_signal_semaphore = setSignalSemaphore;
    return true;
}

void VkContext::attachWindow(ANativeWindow* win) {
    if (surface_) {
        vkDeviceWaitIdle(device_);
        destroySwapchain();
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    window_ = win;
    if (!win) return;
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = win;
    if (vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_) != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed"); surface_ = VK_NULL_HANDLE; return;
    }
    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(gpu_, queueFamily_, surface_, &supported);
    if (!supported) LOGE("vulkan: queue family %u cannot present", queueFamily_);
    createSwapchain();
}

bool VkContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu_, surface_, &caps);

    uint32_t fn = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &fn, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(fn);
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu_, surface_, &fn, formats.data());
    VkSurfaceFormatKHR fmt = formats[0];
    for (auto& f : formats)
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) { fmt = f; break; }

    uint32_t count = std::max(caps.minImageCount + 1, 3u);
    if (caps.maxImageCount) count = std::min(count, caps.maxImageCount);

    extent_ = caps.currentExtent;
    if (extent_.width == UINT32_MAX) {
        extent_.width = (uint32_t)ANativeWindow_getWidth(window_);
        extent_.height = (uint32_t)ANativeWindow_getHeight(window_);
    }

    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    for (auto a : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})
        if (caps.supportedCompositeAlpha & a) { alpha = a; break; }

    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = surface_;
    ci.minImageCount = count;
    ci.imageFormat = fmt.format;
    ci.imageColorSpace = fmt.colorSpace;
    ci.imageExtent = extent_;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Identity lets the compositor rotate if the panel is mounted rotated. M4 can pre-rotate.
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                          ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = alpha;
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR; // vsync; M4 adds Swappy / mailbox options
    ci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed"); swapchain_ = VK_NULL_HANDLE; return false;
    }

    uint32_t n = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    images_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, images_.data());

    cmds_.resize(n);
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool_; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = n;
    vkAllocateCommandBuffers(device_, &ai, cmds_.data());

    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    fences_.resize(n); renderDone_.resize(n); acquireSems_.resize(n + 1);
    for (uint32_t i = 0; i < n; ++i) {
        vkCreateFence(device_, &fci, nullptr, &fences_[i]);
        vkCreateSemaphore(device_, &sci, nullptr, &renderDone_[i]);
    }
    for (auto& s : acquireSems_) vkCreateSemaphore(device_, &sci, nullptr, &s);
    acquireSlot_ = 0;
    LOGI("vulkan: swapchain %ux%u, %u images, format %d", extent_.width, extent_.height, n, fmt.format);
    return true;
}

void VkContext::destroySwapchain() {
    if (!swapchain_) return;
    for (auto f : fences_) vkDestroyFence(device_, f, nullptr);
    for (auto s : renderDone_) vkDestroySemaphore(device_, s, nullptr);
    for (auto s : acquireSems_) vkDestroySemaphore(device_, s, nullptr);
    if (!cmds_.empty()) vkFreeCommandBuffers(device_, pool_, (uint32_t)cmds_.size(), cmds_.data());
    fences_.clear(); renderDone_.clear(); acquireSems_.clear(); cmds_.clear(); images_.clear();
    vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
    acquired_ = false;
}

bool VkContext::beginFrame() {
    if (!swapchain_) return false;
    currentAcquire_ = acquireSems_[acquireSlot_];
    acquireSlot_ = (acquireSlot_ + 1) % (uint32_t)acquireSems_.size();
    VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, currentAcquire_, VK_NULL_HANDLE, &index_);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        vkDeviceWaitIdle(device_); destroySwapchain(); createSwapchain();
        return false;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGE("vkAcquireNextImageKHR: %d", r); return false; }
    vkWaitForFences(device_, 1, &fences_[index_], VK_TRUE, UINT64_MAX);
    acquired_ = true;
    presented_ = false;
    return true;
}

void VkContext::videoRefresh(const void* data, unsigned w, unsigned h) {
    if (!acquired_ || presented_) return;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) { imageW_ = w; imageH_ = h; present(true); }
    else present(false); // dupe: show the last image again
}

void VkContext::endFrame() {
    if (acquired_ && !presented_) present(false); // an acquired image must be presented
    acquired_ = false;
}

void VkContext::present(bool newImage) {
    VkCommandBuffer cmd = cmds_[index_];
    VkImage dst = images_[index_];
    vkResetFences(device_, 1, &fences_[index_]);
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);

    imageBarrier(cmd, dst, kColorRange, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkClearColorValue black{};
    vkCmdClearColorImage(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &kColorRange);

    if (haveImage_ && imageW_ && imageH_) {
        VkImage src = image_.create_info.image;
        VkImageLayout layout = image_.image_layout;
        VkImageSubresourceRange range = image_.create_info.subresourceRange;
        range.levelCount = 1; range.layerCount = 1;
        // Ownership transfer only when the core hands over from another queue family this frame.
        bool transfer = newImage && srcQueueFamily_ != VK_QUEUE_FAMILY_IGNORED && srcQueueFamily_ != queueFamily_;
        uint32_t fromFam = transfer ? srcQueueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        uint32_t toFam = transfer ? queueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        VkImageLayout blitLayout = layout == VK_IMAGE_LAYOUT_GENERAL ? VK_IMAGE_LAYOUT_GENERAL
                                                                     : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        imageBarrier(cmd, src, range, layout, blitLayout,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                     fromFam, toFam);

        // 4:3 letterbox, same as the GLES path. M5 adds widescreen / integer scale.
        int32_t winW = (int32_t)extent_.width, winH = (int32_t)extent_.height;
        int32_t dstH = winH, dstW = winH * 4 / 3;
        if (dstW > winW) { dstW = winW; dstH = winW * 3 / 4; }
        int32_t x = (winW - dstW) / 2, y = (winH - dstH) / 2;
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1};
        blit.srcOffsets[1] = {(int32_t)imageW_, (int32_t)imageH_, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[0] = {x, y, 0};
        blit.dstOffsets[1] = {x + dstW, y + dstH, 1};
        vkCmdBlitImage(cmd, src, blitLayout, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

        imageBarrier(cmd, src, range, blitLayout, layout, VK_ACCESS_TRANSFER_READ_BIT, 0,
                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, toFam, fromFam);
    }

    imageBarrier(cmd, dst, kColorRange, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                 VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    vkEndCommandBuffer(cmd);

    std::vector<VkCommandBuffer> submitCmds;
    std::vector<VkSemaphore> waits{currentAcquire_};
    if (newImage) {
        submitCmds = coreCmds_;
        // Semaphores from set_image are ignored when the core uses set_command_buffers.
        if (coreCmds_.empty()) waits.insert(waits.end(), waitSems_.begin(), waitSems_.end());
    }
    submitCmds.push_back(cmd);
    std::vector<VkPipelineStageFlags> stages(waits.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);
    std::vector<VkSemaphore> signals{renderDone_[index_]};
    if (signalSem_) signals.push_back(signalSem_);

    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = (uint32_t)waits.size(); si.pWaitSemaphores = waits.data(); si.pWaitDstStageMask = stages.data();
    si.commandBufferCount = (uint32_t)submitCmds.size(); si.pCommandBuffers = submitCmds.data();
    si.signalSemaphoreCount = (uint32_t)signals.size(); si.pSignalSemaphores = signals.data();

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &renderDone_[index_];
    pi.swapchainCount = 1; pi.pSwapchains = &swapchain_; pi.pImageIndices = &index_;
    VkResult r;
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        vkQueueSubmit(queue_, 1, &si, fences_[index_]);
        r = vkQueuePresentKHR(queue_, &pi);
    }
    waitSems_.clear(); coreCmds_.clear(); signalSem_ = VK_NULL_HANDLE;
    presented_ = true;
    // SUBOPTIMAL is returned every frame on Android when preTransform != currentTransform
    // (we pick identity on purpose), so only OUT_OF_DATE triggers a rebuild.
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        vkDeviceWaitIdle(device_); destroySwapchain(); createSwapchain();
    }
}

void VkContext::destroy() {
    if (!instance_) return;
    if (device_) vkDeviceWaitIdle(device_);
    destroySwapchain();
    if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (pool_) vkDestroyCommandPool(device_, pool_, nullptr);
    if (negotiation_ && negotiation_->destroy_device && !ownDevice_) negotiation_->destroy_device();
    if (device_) vkDestroyDevice(device_, nullptr); // owned by the frontend either way
    vkDestroyInstance(instance_, nullptr);
    surface_ = VK_NULL_HANDLE; pool_ = VK_NULL_HANDLE; device_ = VK_NULL_HANDLE; instance_ = VK_NULL_HANDLE;
}

// ---- interface callbacks ---------------------------------------------------

void VkContext::setImage(void* h, const retro_vulkan_image* img, uint32_t n, const VkSemaphore* sems, uint32_t fam) {
    auto* self = static_cast<VkContext*>(h);
    self->image_ = *img; // create_info.pNext is not followed
    self->haveImage_ = true;
    self->waitSems_.assign(sems, sems + n);
    self->srcQueueFamily_ = fam;
}
uint32_t VkContext::getSyncIndex(void* h) { return static_cast<VkContext*>(h)->index_; }
uint32_t VkContext::getSyncIndexMask(void* h) {
    size_t n = static_cast<VkContext*>(h)->images_.size();
    return n ? (1u << n) - 1 : 1u;
}
void VkContext::setCommandBuffers(void* h, uint32_t n, const VkCommandBuffer* cmds) {
    auto* self = static_cast<VkContext*>(h);
    self->coreCmds_.assign(cmds, cmds + n);
}
void VkContext::waitSyncIndex(void* h) {
    auto* self = static_cast<VkContext*>(h);
    if (self->fences_.empty()) return;
    vkWaitForFences(self->device_, 1, &self->fences_[self->index_], VK_TRUE, UINT64_MAX);
}
void VkContext::lockQueue(void* h) { static_cast<VkContext*>(h)->queueMutex_.lock(); }
void VkContext::unlockQueue(void* h) { static_cast<VkContext*>(h)->queueMutex_.unlock(); }
void VkContext::setSignalSemaphore(void* h, VkSemaphore s) { static_cast<VkContext*>(h)->signalSem_ = s; }
