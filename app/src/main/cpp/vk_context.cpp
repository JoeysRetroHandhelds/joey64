#include "joeylog.h"
#include "vk_context.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <string>
#include <adrenotools/driver.h>
// Dev env N64_LOG_ERR=1 logs info at error level, for devices whose logd drops everything
// below error (Retroid Pocket Duo).
#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

namespace {
int64_t monoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}
#define VK_FUNCS(X) X(vkAcquireNextImageKHR) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkCmdBlitImage) X(vkCmdClearColorImage) X(vkCmdPipelineBarrier) X(vkCreateAndroidSurfaceKHR) X(vkCreateCommandPool) X(vkCreateDevice) X(vkCreateFence) X(vkCreateSemaphore) X(vkCreateSwapchainKHR) X(vkDestroyCommandPool) X(vkDestroyDevice) X(vkDestroyFence) X(vkDestroyInstance) X(vkDestroySemaphore) X(vkDestroySurfaceKHR) X(vkDestroySwapchainKHR) X(vkDeviceWaitIdle) X(vkEndCommandBuffer) X(vkEnumerateDeviceExtensionProperties) X(vkEnumeratePhysicalDevices) X(vkFreeCommandBuffers) X(vkGetDeviceQueue) X(vkGetPhysicalDeviceFeatures2) X(vkGetPhysicalDeviceProperties) X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) X(vkGetPhysicalDeviceSurfaceFormatsKHR) X(vkGetPhysicalDeviceSurfacePresentModesKHR) X(vkGetPhysicalDeviceSurfaceSupportKHR) X(vkGetSwapchainImagesKHR) X(vkQueuePresentKHR) X(vkQueueSubmit) X(vkResetCommandBuffer) X(vkResetFences) X(vkWaitForFences) X(vkGetDeviceProcAddr) X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory) X(vkBindImageMemory) X(vkGetPhysicalDeviceMemoryProperties) X(vkQueueWaitIdle) X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) X(vkMapMemory) X(vkUnmapMemory) X(vkCmdCopyImageToBuffer) X(vkInvalidateMappedMemoryRanges)
#define VK_DECLARE(f) PFN_##f f = nullptr;
VK_FUNCS(VK_DECLARE)
PFN_vkCreateInstance vkCreateInstance = nullptr;

std::string g_driverDir, g_driverName, g_hookDir, g_tmpDir;
// Loaded once per process and never closed (drivers like Turnip don't survive dlclose); shared by
// probe() and init(). g_customFailed: the custom driver didn't load or has no usable GPU.
void* g_sysLib = nullptr;
void* g_customLib = nullptr;
bool g_customFailed = false;

void* systemLibrary() {
    if (!g_sysLib) g_sysLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    return g_sysLib;
}

void* customLibrary() {
    if (!g_customLib && !g_customFailed && !g_driverName.empty()) {
        g_customLib = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, g_tmpDir.c_str(), g_hookDir.c_str(),
                                                 g_driverDir.c_str(), g_driverName.c_str(), nullptr, nullptr);
        if (g_customLib) LOGI("vulkan: loaded custom driver %s%s", g_driverDir.c_str(), g_driverName.c_str());
        else { LOGE("vulkan: custom driver %s failed to load", g_driverName.c_str()); g_customFailed = true; }
    }
    return g_customLib;
}
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

void VkContext::setCustomDriver(const char* driverDir, const char* libName, const char* hookDir,
                                const char* tmpDir) {
    g_driverDir = driverDir; g_driverName = libName; g_hookDir = hookDir; g_tmpDir = tmpDir;
    g_customLib = nullptr; g_customFailed = false;
}

std::string VkContext::probe(bool custom) {
    void* lib = custom ? customLibrary() : systemLibrary();
    if (!lib) return "";
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    auto create = gipa ? reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance")) : nullptr;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    std::string out;
    if (create && create(&ici, nullptr, &inst) == VK_SUCCESS) {
        auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
        auto getProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(inst, "vkGetPhysicalDeviceProperties"));
        auto destroyInst = reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));
        auto getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(gipa(inst, "vkGetPhysicalDeviceFeatures2"));
        uint32_t n = 1;
        VkPhysicalDevice gpu = VK_NULL_HANDLE;
        VkResult r = enumerate(inst, &n, &gpu);
        if ((r == VK_SUCCESS || r == VK_INCOMPLETE) && n && gpu) {
            VkPhysicalDeviceProperties props;
            getProps(gpu, &props);
            // parallel-RDP needs 16-bit storage buffers (storageBuffer16BitAccess); without them its init fails.
            // The Adreno 610 (Snapdragon 665) has them on neither its Qualcomm driver nor Turnip.
            int s16 = -1;
            if (getFeatures2 && props.apiVersion >= VK_API_VERSION_1_1) {
                VkPhysicalDevice16BitStorageFeatures f16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
                VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
                f2.pNext = &f16;
                getFeatures2(gpu, &f2);
                s16 = f16.storageBuffer16BitAccess ? 1 : 0;
            }
            char buf[320];
            snprintf(buf, sizeof buf, "%u|%u.%u|%d|%s", props.vendorID, VK_VERSION_MAJOR(props.apiVersion),
                     VK_VERSION_MINOR(props.apiVersion), s16, props.deviceName);
            out = buf;
        }
        destroyInst(inst, nullptr);
    }
    if (out.empty() && custom) { g_customFailed = true; g_customLib = nullptr; }
    LOGI("vulkan probe (%s): %s", custom ? "custom" : "system", out.empty() ? "no usable GPU" : out.c_str());
    return out;
}

bool VkContext::loadLibrary() {
    lib_ = customLibrary();
    if (!lib_ && !g_driverName.empty()) LOGE("vulkan: custom driver %s unusable, using system driver", g_driverName.c_str());
    if (!lib_) lib_ = systemLibrary();
    if (!lib_) { LOGE("cannot load libvulkan.so"); return false; }
    gipa_ = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib_, "vkGetInstanceProcAddr"));
    if (!gipa_) { LOGE("no vkGetInstanceProcAddr"); return false; }
    vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa_(VK_NULL_HANDLE, "vkCreateInstance"));
    return vkCreateInstance != nullptr;
}

bool VkContext::init(const retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    negotiation_ = negotiation;
    if (!loadLibrary()) return false;

    VkApplicationInfo defaultApp{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    defaultApp.pApplicationName = "Joey64";
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
#define VK_LOAD(f) f = reinterpret_cast<PFN_##f>(gipa_(instance_, #f));
    VK_FUNCS(VK_LOAD)
#undef VK_LOAD

    // Display timing for the latency stats, if the first GPU (the one the core picks) offers it.
    bool displayTiming = false;
    {
        uint32_t n = 1;
        VkPhysicalDevice first = VK_NULL_HANDLE;
        VkResult r = vkEnumeratePhysicalDevices(instance_, &n, &first);
        if ((r == VK_SUCCESS || r == VK_INCOMPLETE) && first) {
            uint32_t en = 0;
            vkEnumerateDeviceExtensionProperties(first, nullptr, &en, nullptr);
            std::vector<VkExtensionProperties> avail(en);
            vkEnumerateDeviceExtensionProperties(first, nullptr, &en, avail.data());
            displayTiming = hasExt(avail, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
        }
    }

    // Let the core create the device (Granite knows which extensions/features parallel-RDP wants).
    if (negotiation_ && negotiation_->create_device) {
        retro_vulkan_context ctx{};
        const char* devExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME};
        VkPhysicalDeviceFeatures noFeatures{}; // Granite dereferences this, so never pass null
        if (negotiation_->create_device(&ctx, instance_, VK_NULL_HANDLE, VK_NULL_HANDLE, gipa_,
                                        devExts, displayTiming ? 2 : 1, nullptr, 0, &noFeatures)) {
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
        for (const char* e : {VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME, "VK_KHR_8bit_storage", "VK_KHR_16bit_storage", "VK_KHR_shader_float16_int8",
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

    if (displayTiming)
        getPastTiming_ = reinterpret_cast<PFN_vkGetPastPresentationTimingGOOGLE>(
            vkGetDeviceProcAddr(device_, "vkGetPastPresentationTimingGOOGLE"));
    if (displayTiming)
        getRefreshCycle_ = reinterpret_cast<PFN_vkGetRefreshCycleDurationGOOGLE>(
            vkGetDeviceProcAddr(device_, "vkGetRefreshCycleDurationGOOGLE"));
    LOGI("vulkan: display timing %s", getPastTiming_ ? "on" : "unavailable");

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(gpu_, &props);
    LOGI("vulkan: %s, api %u.%u.%u, driver 0x%x", props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion), props.driverVersion);

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    vkCreateCommandPool(device_, &pci, nullptr, &pool_);
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool_; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = kSyncFrames;
    vkAllocateCommandBuffers(device_, &ai, frameCmds_);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (auto& f : frameFences_) vkCreateFence(device_, &fci, nullptr, &f);
    const char* dupes = getenv("N64_SKIP_DUPES");
    skipDupes_ = !(dupes && *dupes == '0');
    const char* early = getenv("N64_EARLY_ACQUIRE");
    earlyAcquire_ = early ? atoi(early) : 0; // new frames in a row before acquiring early (2 = first try)
    if (earlyAcquire_ > 0) LOGI("vulkan: early acquire after %d new frames in a row", earlyAcquire_);

    iface_.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface_.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface_.handle = this;
    iface_.instance = instance_;
    iface_.gpu = gpu_;
    iface_.device = device_;
    iface_.get_device_proc_addr = vkGetDeviceProcAddr;
    iface_.get_instance_proc_addr = gipa_;
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
    startSwapchain();
}

void VkContext::startSwapchain() {
    createSwapchain();
}

void VkContext::resizeSwapchain() {
    if (!surface_) return;
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    startSwapchain();
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
    // EXPERIMENT: N64_SWAP_IMAGES=<n> asks for n swapchain images (2 = one frame less queued, less lag; the Flip
    // fell to ~40 fps with 2, the faster devices are untested). Never below what the driver allows.
    if (const char* env = getenv("N64_SWAP_IMAGES"))
        if (int n = atoi(env); n > 0) count = std::max((uint32_t)n, caps.minImageCount);
    if (caps.maxImageCount) count = std::min(count, caps.maxImageCount);
    LOGI("vulkan: swapchain images: min %u max %u, asking %u", caps.minImageCount, caps.maxImageCount, count);

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
    format_ = fmt.format;
    ci.imageColorSpace = fmt.colorSpace;
    ci.imageExtent = extent_;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Identity lets the compositor rotate if the panel is mounted rotated. M4 can pre-rotate.
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                          ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = alpha;
    // FIFO = vsync (M4 adds Swappy). Unthrottled (fast-forward) prefers mailbox, then immediate.
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    if (wantUnthrottled_) {
        uint32_t mn = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(gpu_, surface_, &mn, nullptr);
        std::vector<VkPresentModeKHR> modes(mn);
        vkGetPhysicalDeviceSurfacePresentModesKHR(gpu_, surface_, &mn, modes.data());
        for (auto want : {VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR})
            if (std::find(modes.begin(), modes.end(), want) != modes.end()) { ci.presentMode = want; break; }
    }
    unthrottled_ = wantUnthrottled_;
    ci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed"); swapchain_ = VK_NULL_HANDLE; return false;
    }

    uint32_t n = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    images_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, images_.data());

    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    renderDone_.resize(n); acquireSems_.resize(n + 1);
    for (auto& s : renderDone_) vkCreateSemaphore(device_, &sci, nullptr, &s);
    for (auto& s : acquireSems_) vkCreateSemaphore(device_, &sci, nullptr, &s);
    acquireSlot_ = 0;
    repaint_ = true; // the last image isn't on the new swapchain yet
    LOGI("vulkan: swapchain %ux%u, %u images, format %d, present mode %d", extent_.width, extent_.height,
         n, fmt.format, ci.presentMode);
    updateSwapInterval();
    return true;
}

// Re-read now and then: the system can change the refresh rate under us (battery saver, thermal).
void VkContext::updateSwapInterval() {
    uint32_t interval = 1;
    VkRefreshCycleDurationGOOGLE rc{};
    if (getRefreshCycle_ && swapchain_ && getRefreshCycle_(device_, swapchain_, &rc) == VK_SUCCESS && rc.refreshDuration) {
        double ratio = 1e9 / (double)rc.refreshDuration / contentHz_;
        // Only whole multiples pace evenly (120 Hz for 60 fps; not 90 Hz, or 120 Hz for PAL's 50).
        uint32_t n = (uint32_t)(ratio + 0.5);
        if (n >= 2 && std::abs(ratio - n) < 0.03 * n) interval = n;
        refreshNs_ = rc.refreshDuration;
        // Opt-in for now: presenting twice (1) fills the swapchain queue, which cost 8-16 ms of
        // poll->display (Duo 22-25 -> 31-39 ms, Portal 18-25 -> 32-41 ms) for even pacing. Timed
        // pacing (2) costs 2-7 ms (Portal, Duo, Odin 3) for nearly the same evenness.
        const char* si = getenv("N64_SWAP_INTERVAL");
        swapMode_ = si ? atoi(si) : 0;
        if (swapMode_ == 2 && !getPastTiming_) swapMode_ = 0; // timed pacing needs display timing
        if (swapMode_ == 0) interval = 1;
        if (interval != swapInterval_)
            LOGI("vulkan: refresh %.1f Hz, game %.2f fps: swap interval %u%s", 1e9 / (double)rc.refreshDuration,
                 contentHz_, interval, swapMode_ == 2 ? " (timed)" : "");
    }
    swapInterval_ = interval;
}

void VkContext::destroySwapchain() {
    if (!swapchain_) return;
    for (auto s : renderDone_) vkDestroySemaphore(device_, s, nullptr);
    for (auto s : acquireSems_) vkDestroySemaphore(device_, s, nullptr);
    renderDone_.clear(); acquireSems_.clear(); images_.clear();
    vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
    acquired_ = false;
}

bool VkContext::beginFrame(bool show) {
    if (!swapchain_) return false;
    if (wantUnthrottled_ != unthrottled_) { vkDeviceWaitIdle(device_); destroySwapchain(); createSwapchain(); }
    skip_ = !show;
    if (skip_) { // run the core's work without touching the swapchain
        sync_ = (sync_ + 1) % kSyncFrames;
        vkWaitForFences(device_, 1, &frameFences_[sync_], VK_TRUE, UINT64_MAX);
        acquired_ = true;
        presented_ = false;
        return true;
    }
    paceFrame();
    if (skipDupes_ && earlyAcquire_ > 0 && newStreak_ >= earlyAcquire_) {
        // every-frame stretch: acquire before the run, so a full swapchain is waited on before the input poll
        if (!acquireImage()) return false;
        sync_ = (sync_ + 1) % kSyncFrames;
        vkWaitForFences(device_, 1, &frameFences_[sync_], VK_TRUE, UINT64_MAX);
        acquired_ = true;
        presented_ = false;
        return true;
    }
    if (skipDupes_) { // acquire once the core reports a new frame (present), so repeats cost nothing
        sync_ = (sync_ + 1) % kSyncFrames;
        vkWaitForFences(device_, 1, &frameFences_[sync_], VK_TRUE, UINT64_MAX);
        if (frameStartNs_ == -1) frameStartNs_ = monoNs(); // the pacing budget includes the run
        needAcquire_ = true;
        acquired_ = true;
        presented_ = false;
        return true;
    }
    if (!acquireImage()) return false;
    sync_ = (sync_ + 1) % kSyncFrames;
    vkWaitForFences(device_, 1, &frameFences_[sync_], VK_TRUE, UINT64_MAX);
    acquired_ = true;
    presented_ = false;
    return true;
}

bool VkContext::acquireImage() {
    currentAcquire_ = acquireSems_[acquireSlot_];
    acquireSlot_ = (acquireSlot_ + 1) % (uint32_t)acquireSems_.size();
    VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, currentAcquire_, VK_NULL_HANDLE, &index_);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        vkDeviceWaitIdle(device_); destroySwapchain(); createSwapchain();
        return false;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGE("vkAcquireNextImageKHR: %d", r); return false; }
    if (frameStartNs_ == -1) frameStartNs_ = monoNs();
    return true;
}

void VkContext::videoRefresh(const void* data, unsigned w, unsigned h) {
    if (!acquired_ || presented_) return;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) { imageW_ = w; imageH_ = h; present(true); }
    else present(false); // dupe: show the last image again
}

// Timed pacing (swapMode_ 2). Display slots are every swapInterval_-th refresh, on the grid of a
// past frame's actual display time. Each frame targets the first slot it can make and starts
// (polls input, runs) just in time for it: frame work so far (decaying peak) plus two refreshes
// for the compositor and a margin. Present asks for that slot, so every frame shows the same number
// of refreshes without presenting twice, and the queue stays short.
void VkContext::paceFrame() {
    frameStartNs_ = 0;
    if (swapMode_ != 2 || swapInterval_ < 2 || unthrottled_ || !refreshNs_) return;
    uint64_t anchor; double extra;
    {
        std::lock_guard<std::mutex> lk(timingMutex_);
        anchor = lastActual_; extra = extraLeadNs_;
    }
    int64_t now = monoNs();
    if (!anchor) { frameStartNs_ = -1; return; } // no display time seen yet
    uint64_t period = refreshNs_ * swapInterval_;
    // The compositor needs a present about two refreshes before display (measured on the Portal;
    // earliestPresentTime can't be used for this: it includes our own queued frames, and feeding
    // it back made the budget run away). GPU time after the present call isn't in workMaxNs_, so
    // frames that still miss their slot add lead (extraLeadNs_, up to two refreshes).
    uint64_t budget = (uint64_t)(std::min(workMaxNs_, (double)period) + extra) + 2 * refreshNs_ + 1000000;
    uint64_t earliest = (uint64_t)now + budget;
    uint64_t k = earliest > anchor ? (earliest - anchor + period - 1) / period : 0;
    uint64_t target = anchor + k * period;
    if (lastTargetNs_ && target + refreshNs_ / 2 < lastTargetNs_ + period)
        target = lastTargetNs_ + period; // never two frames in one slot
    lastTargetNs_ = target;
    desiredNs_ = target - refreshNs_ / 2; // half a refresh early: the compositor rounds up to the slot
    struct timespec ts{(time_t)((target - budget) / 1000000000ull), (long)((target - budget) % 1000000000ull)};
    if ((int64_t)(target - budget) > now) clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
    frameStartNs_ = -1; // set once the image is acquired: work excludes swapchain waits
}

void VkContext::endFrame() {
    bool begun = acquired_;
    if (acquired_ && !presented_) present(false); // an acquired image must be presented
    bool shown = begun && !skip_ && swapMode_ == 1 && !needAcquire_; // not a skipped repeat
    acquired_ = false;
    needAcquire_ = false;
    // Swap interval: present the frame again, so with FIFO each one is shown for swapInterval_
    // refreshes and emulation is paced to the game's rate. Not while unthrottled (fast-forward).
    if (!shown || unthrottled_ || swapInterval_ < 2) return;
    int64_t poll = pollNs_;
    pollNs_ = 0; // latency is measured on the frame's first present
    for (uint32_t k = 1; k < swapInterval_; ++k) {
        if (!beginFrame(true)) break;
        repaint_ = true; // the repeat is the point here
        present(false);
        acquired_ = false;
        needAcquire_ = false;
    }
    pollNs_ = poll;
}

void VkContext::present(bool newImage) {
    if (skip_) { submitSkipped(newImage); return; }
    if (needAcquire_) {
        // N64_SKIP_DUPES: a repeat frame leaves the last image up (FIFO keeps showing it); presenting
        // it again was a blit, a submit and a present per refresh while games draw 20-30 frames a second.
        if (!newImage && !repaint_) { presented_ = true; newStreak_ = 0; return; }
        if (!acquireImage()) { presented_ = true; return; }
        needAcquire_ = false;
    }
    newStreak_ = newImage ? newStreak_ + 1 : 0;
    repaint_ = false;
    VkCommandBuffer cmd = frameCmds_[sync_];
    VkImage dst = images_[index_];
    VkExtent2D dstExtent = extent_;
    vkResetFences(device_, 1, &frameFences_[sync_]);
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

        // 4:3 letterbox, same as the GLES path. Dev switch N64_ASPECT=16:9 (or any W:H) shows it
        // wider, for widescreen codes, until Joey designs the menu spot.
        static const std::pair<int32_t, int32_t> aspect = [] {
            int w = 4, h = 3;
            if (const char* a = getenv("N64_ASPECT")) if (sscanf(a, "%d:%d", &w, &h) != 2 || w <= 0 || h <= 0) { w = 4; h = 3; }
            return std::pair<int32_t, int32_t>(w, h);
        }();
        int32_t winW = (int32_t)dstExtent.width, winH = (int32_t)dstExtent.height;
        int32_t dstH = winH, dstW = winH * aspect.first / aspect.second;
        if (dstW > winW) { dstW = winW; dstH = winW * aspect.second / aspect.first; }
        int32_t x = (winW - dstW) / 2, y = (winH - dstH) / 2;
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1};
        blit.srcOffsets[1] = {(int32_t)imageW_, (int32_t)imageH_, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[0] = {x, y, 0};
        blit.dstOffsets[1] = {x + dstW, y + dstH, 1};
        // Sharp display: nearest to the first whole multiple at least the screen size, then a linear step
        // down (under 2:1, so the 2x2 blit filter sees every pixel). Rounding down instead left OoT's
        // 640x480 at 1x on the Flip's 752-line screen, a plain linear 1.57x stretch.
        uint32_t kx = sharp_ ? std::max(1, (dstW + (int32_t)imageW_ - 1) / (int32_t)imageW_) : 1;
        uint32_t ky = sharp_ ? std::max(1, (dstH + (int32_t)imageH_ - 1) / (int32_t)imageH_) : 1;
        if ((kx > 1 || ky > 1) && ensureSharpImage(imageW_ * kx, imageH_ * ky, image_.create_info.format)) {
            VkImageSubresourceRange whole{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            // The previous frame's linear blit may still read it: wait for that before writing.
            imageBarrier(cmd, sharpImage_, whole, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageBlit up = blit;
            up.dstOffsets[0] = {0, 0, 0};
            up.dstOffsets[1] = {(int32_t)sharpW_, (int32_t)sharpH_, 1};
            vkCmdBlitImage(cmd, src, blitLayout, sharpImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &up, VK_FILTER_NEAREST);
            imageBarrier(cmd, sharpImage_, whole, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageBlit down = blit;
            down.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            down.srcOffsets[1] = {(int32_t)sharpW_, (int32_t)sharpH_, 1};
            vkCmdBlitImage(cmd, sharpImage_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &down, VK_FILTER_LINEAR);
        } else {
            vkCmdBlitImage(cmd, src, blitLayout, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        }

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
    VkPresentTimeGOOGLE when{++presentId_, desiredNs_};
    desiredNs_ = 0;
    if (frameStartNs_ > 0) { // track how long frames take, for the pacing budget
        workMaxNs_ = std::max((double)(monoNs() - frameStartNs_), workMaxNs_ * 0.995);
        frameStartNs_ = 0;
    }
    VkPresentTimesInfoGOOGLE times{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};
    if (getPastTiming_) {
        presentPollNs_[presentId_ % kTimingRing] = pollNs_;
        times.swapchainCount = 1; times.pTimes = &when;
        pi.pNext = &times;
        int64_t t = monoNs();
        std::lock_guard<std::mutex> lk(timingMutex_);
        if (lastCallNs_ && t - lastCallNs_ < 100000000) {
            double d = (t - lastCallNs_) / 1e6;
            callSum_ += d; callSq_ += d * d; ++callN_;
        }
        lastCallNs_ = t;
    }
    VkResult r;
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        vkQueueSubmit(queue_, 1, &si, frameFences_[sync_]);
        r = vkQueuePresentKHR(queue_, &pi);
    }
    waitSems_.clear(); coreCmds_.clear(); signalSem_ = VK_NULL_HANDLE;
    presented_ = true;
    collectTiming();
    if ((presentId_ & 127) == 0) updateSwapInterval();
    // SUBOPTIMAL is returned every frame on Android when preTransform != currentTransform
    // (we pick identity on purpose), so only OUT_OF_DATE triggers a rebuild.
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        vkDeviceWaitIdle(device_); destroySwapchain(); createSwapchain();
    }
}

void VkContext::collectTiming() {
    if (!getPastTiming_ || !swapchain_) return;
    std::lock_guard<std::mutex> lk(timingMutex_);
    VkPastPresentationTimingGOOGLE past[8];
    uint32_t n = 8;
    if (getPastTiming_(device_, swapchain_, &n, past) < 0) return;
    for (uint32_t i = 0; i < n; ++i) {
        if (presentId_ - past[i].presentID >= kTimingRing) continue; // too old for the ring
        int64_t poll = presentPollNs_[past[i].presentID % kTimingRing];
        if (!poll || !past[i].actualPresentTime) continue;
        double ms = ((int64_t)past[i].actualPresentTime - poll) / 1e6;
        if (ms <= 0 || ms > 500) continue;
        latSumMs_ += ms; latCount_++;
        latMaxMs_ = std::max(latMaxMs_, ms);
        // Dev check N64_PRESENT_MARGIN=1 (2026-11-05 recheck): log presentMargin (how long before its display slot the
        // compositor had the frame) as mean/min/max over every 300 frames, to see whether the driver reports usable values.
        static const bool marginLog = getenv("N64_PRESENT_MARGIN") && *getenv("N64_PRESENT_MARGIN") == '1';
        if (marginLog) {
            static double mSum = 0, mMin = 1e18, mMax = -1e18; static int mN = 0;
            double m = (double)past[i].presentMargin / 1e6;
            mSum += m; mMin = std::min(mMin, m); mMax = std::max(mMax, m);
            if (++mN == 300) {
                LOGI("vulkan: presentMargin over 300 frames: mean %.2f ms, min %.2f, max %.2f", mSum / mN, mMin, mMax);
                mSum = 0; mMin = 1e18; mMax = -1e18; mN = 0;
            }
        }
        if (swapMode_ == 2 && past[i].desiredPresentTime) {
            // Only misses where the frame itself came too late (earliest possible display past the
            // target): more lead can't fix slots the compositor skips on its own (Portal).
            bool late = (int64_t)(past[i].actualPresentTime - past[i].desiredPresentTime) > (int64_t)(refreshNs_ * 3 / 4) &&
                        (int64_t)(past[i].earliestPresentTime - past[i].desiredPresentTime) > (int64_t)(refreshNs_ * 3 / 4);
            extraLeadNs_ = late ? std::min(extraLeadNs_ + refreshNs_ / 2.0, 2.0 * refreshNs_) : extraLeadNs_ * 0.995;
        }
        if (lastActual_ && past[i].presentID == lastId_ + 1) {
            // Even = the gap is a whole number of game frames (one, or with repeats skipped any number:
            // OoT's 20 fps is 3), within the tolerance of a 120 Hz refresh either way.
            double gap = (int64_t)(past[i].actualPresentTime - lastActual_) / 1e6, frame = 1000.0 / contentHz_;
            double k = skipDupes_ ? std::max(1.0, std::round(gap / frame)) : 1.0;
            if (gap - k * frame < -2.7 || gap - k * frame > 2.8) ++uneven_;
            if (refreshNs_) { // how many refreshes each frame stayed up (cadence)
                long n = std::lround(gap * 1e6 / (double)refreshNs_);
                ++shownFor_[std::max(1L, std::min(4L, n)) - 1];
            }
        }
        lastActual_ = past[i].actualPresentTime; lastId_ = past[i].presentID;
    }
}

bool VkContext::takeLatency(double& meanMs, double& maxMs, int& uneven) {
    std::lock_guard<std::mutex> lk(timingMutex_);
    if (!latCount_) return false;
    meanMs = latSumMs_ / latCount_; maxMs = latMaxMs_; uneven = uneven_; uneven_ = 0;
    latSumMs_ = latMaxMs_ = 0; latCount_ = 0;
    return true;
}

std::string VkContext::takeCadence() {
    std::lock_guard<std::mutex> lk(timingMutex_);
    char buf[128] = "";
    if (callN_) {
        double mean = callSum_ / callN_, sd = std::sqrt(std::max(0.0, callSq_ / callN_ - mean * mean));
        snprintf(buf, sizeof buf, ", shown 1/2/3/4+ refreshes %d/%d/%d/%d, present every %.2f ms (sd %.2f)",
                 shownFor_[0], shownFor_[1], shownFor_[2], shownFor_[3], mean, sd);
    }
    shownFor_[0] = shownFor_[1] = shownFor_[2] = shownFor_[3] = 0;
    callSum_ = callSq_ = 0; callN_ = 0;
    return buf;
}

// Skipped frame: submit only what the core handed over (so its semaphores are consumed and the
// sync index's fence signals), with no blit or present.
void VkContext::submitSkipped(bool newImage) {
    vkResetFences(device_, 1, &frameFences_[sync_]);
    std::vector<VkSemaphore> waits;
    std::vector<VkCommandBuffer> cmds;
    if (newImage) {
        cmds = coreCmds_;
        if (coreCmds_.empty()) waits = waitSems_;
    }
    std::vector<VkPipelineStageFlags> stages(waits.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = (uint32_t)waits.size(); si.pWaitSemaphores = waits.data(); si.pWaitDstStageMask = stages.data();
    si.commandBufferCount = (uint32_t)cmds.size(); si.pCommandBuffers = cmds.data();
    si.signalSemaphoreCount = signalSem_ ? 1 : 0; si.pSignalSemaphores = &signalSem_;
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        vkQueueSubmit(queue_, 1, &si, frameFences_[sync_]);
    }
    waitSems_.clear(); coreCmds_.clear(); signalSem_ = VK_NULL_HANDLE;
    presented_ = true;
}

void VkContext::destroy() {
    if (!instance_) return;
    if (device_) vkDeviceWaitIdle(device_);
    destroySharpImage();
    destroySwapchain();
    if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    for (auto& f : frameFences_) if (f) { vkDestroyFence(device_, f, nullptr); f = VK_NULL_HANDLE; }
    if (pool_) vkDestroyCommandPool(device_, pool_, nullptr); // frees frameCmds_
    if (negotiation_ && negotiation_->destroy_device && !ownDevice_) negotiation_->destroy_device();
    if (device_) vkDestroyDevice(device_, nullptr); // owned by the frontend either way
    vkDestroyInstance(instance_, nullptr);
    lib_ = nullptr; // stays loaded for the process (see systemLibrary)
    surface_ = VK_NULL_HANDLE; pool_ = VK_NULL_HANDLE; device_ = VK_NULL_HANDLE; instance_ = VK_NULL_HANDLE;
}

// ---- screenshots ------------------------------------------------------------

bool VkContext::capture(std::vector<uint8_t>& rgba, unsigned& w, unsigned& h) {
    if (!device_ || !haveImage_ || !imageW_ || !imageH_) return false;
    VkFormat fmt = image_.create_info.format;
    bool bgra = fmt == VK_FORMAT_B8G8R8A8_UNORM || fmt == VK_FORMAT_B8G8R8A8_SRGB;
    if (!bgra && fmt != VK_FORMAT_R8G8B8A8_UNORM && fmt != VK_FORMAT_R8G8B8A8_SRGB) {
        LOGE("vulkan: capture: the core's image format %d isn't 8-bit RGBA/BGRA", fmt);
        return false;
    }
    w = imageW_; h = imageH_;
    VkDeviceSize size = (VkDeviceSize)w * h * 4;
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool ok = false;
    do {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = size; bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT; bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &bci, nullptr, &buf) != VK_SUCCESS) break;
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(device_, buf, &req);
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
        uint32_t type = UINT32_MAX;
        for (uint32_t t = 0; t < mp.memoryTypeCount && type == UINT32_MAX; ++t)
            if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) type = t;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size; mai.memoryTypeIndex = type;
        if (type == UINT32_MAX || vkAllocateMemory(device_, &mai, nullptr, &mem) != VK_SUCCESS) break;
        vkBindBufferMemory(device_, buf, mem, 0);

        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool = pool_; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(device_, &ai, &cmd) != VK_SUCCESS) { cmd = VK_NULL_HANDLE; break; }
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (vkCreateFence(device_, &fci, nullptr, &fence) != VK_SUCCESS) { fence = VK_NULL_HANDLE; break; }

        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        VkImage src = image_.create_info.image;
        VkImageLayout layout = image_.image_layout;
        VkImageSubresourceRange range = image_.create_info.subresourceRange;
        range.levelCount = 1; range.layerCount = 1;
        VkImageLayout copyLayout = layout == VK_IMAGE_LAYOUT_GENERAL ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        // The queue already ran the frame's present (same queue, in order); the barrier orders the copy after it.
        imageBarrier(cmd, src, range, layout, copyLayout,
                     VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1};
        region.imageExtent = {w, h, 1};
        vkCmdCopyImageToBuffer(cmd, src, copyLayout, buf, 1, &region);
        imageBarrier(cmd, src, range, copyLayout, layout, VK_ACCESS_TRANSFER_READ_BIT, 0,
                     VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        VkBufferMemoryBarrier bb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        bb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; bb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        bb.srcQueueFamilyIndex = bb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bb.buffer = buf; bb.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
        {
            std::lock_guard<std::mutex> lk(queueMutex_);
            if (vkQueueSubmit(queue_, 1, &si, fence) != VK_SUCCESS) break;
        }
        if (vkWaitForFences(device_, 1, &fence, VK_TRUE, 2000000000ull) != VK_SUCCESS) {
            // The copy may still be running: wait it out before its buffer and command buffer are freed.
            LOGE("vulkan: capture: GPU didn't finish in 2 s, waiting for the queue");
            std::lock_guard<std::mutex> lk(queueMutex_);
            vkQueueWaitIdle(queue_);
            break;
        }
        void* p = nullptr;
        if (vkMapMemory(device_, mem, 0, VK_WHOLE_SIZE, 0, &p) != VK_SUCCESS) break;
        VkMappedMemoryRange mr{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        mr.memory = mem; mr.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(device_, 1, &mr);
        rgba.assign((const uint8_t*)p, (const uint8_t*)p + size);
        vkUnmapMemory(device_, mem);
        if (bgra) for (size_t i = 0; i < rgba.size(); i += 4) std::swap(rgba[i], rgba[i + 2]);
        for (size_t i = 3; i < rgba.size(); i += 4) rgba[i] = 255;   // the N64 picture has no meaningful alpha
        ok = true;
    } while (false);
    if (fence) vkDestroyFence(device_, fence, nullptr);
    if (cmd) vkFreeCommandBuffers(device_, pool_, 1, &cmd);
    if (buf) vkDestroyBuffer(device_, buf, nullptr);
    if (mem) vkFreeMemory(device_, mem, nullptr);
    if (!ok) LOGE("vulkan: capture failed (%ux%u)", w, h);
    return ok;
}

// ---- sharp display prescale image -----------------------------------------

bool VkContext::ensureSharpImage(uint32_t w, uint32_t h, VkFormat format) {
    if (sharpImage_ && w == sharpW_ && h == sharpH_ && format == sharpFormat_) return true;
    if (sharpImage_) { // earlier frames may still read the old one (rare: the game changed resolution)
        std::lock_guard<std::mutex> lk(queueMutex_);
        vkQueueWaitIdle(queue_);
    }
    destroySharpImage();
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = format; ci.extent = {w, h, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &ci, nullptr, &sharpImage_) != VK_SUCCESS) { sharpImage_ = VK_NULL_HANDLE; return false; }
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, sharpImage_, &req);
    uint32_t type = UINT32_MAX;
    for (uint32_t t = 0; t < mp.memoryTypeCount && type == UINT32_MAX; ++t)
        if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) type = t;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size; mai.memoryTypeIndex = type;
    if (type == UINT32_MAX || vkAllocateMemory(device_, &mai, nullptr, &sharpMem_) != VK_SUCCESS) {
        LOGE("vulkan: sharp image memory failed"); destroySharpImage(); return false;
    }
    vkBindImageMemory(device_, sharpImage_, sharpMem_, 0);
    sharpW_ = w; sharpH_ = h; sharpFormat_ = format;
    LOGI("vulkan: sharp display prescale %ux%u", w, h);
    return true;
}

void VkContext::destroySharpImage() {
    if (sharpImage_) vkDestroyImage(device_, sharpImage_, nullptr);
    if (sharpMem_) vkFreeMemory(device_, sharpMem_, nullptr);
    sharpImage_ = VK_NULL_HANDLE; sharpMem_ = VK_NULL_HANDLE; sharpW_ = sharpH_ = 0;
}

// ---- interface callbacks ---------------------------------------------------

void VkContext::setImage(void* h, const retro_vulkan_image* img, uint32_t n, const VkSemaphore* sems, uint32_t fam) {
    auto* self = static_cast<VkContext*>(h);
    self->image_ = *img; // create_info.pNext is not followed
    self->haveImage_ = true;
    self->waitSems_.assign(sems, sems + n);
    self->srcQueueFamily_ = fam;
}
uint32_t VkContext::getSyncIndex(void* h) { return static_cast<VkContext*>(h)->sync_; }
uint32_t VkContext::getSyncIndexMask(void*) { return (1u << kSyncFrames) - 1; }
void VkContext::setCommandBuffers(void* h, uint32_t n, const VkCommandBuffer* cmds) {
    auto* self = static_cast<VkContext*>(h);
    self->coreCmds_.assign(cmds, cmds + n);
}
void VkContext::waitSyncIndex(void* h) {
    auto* self = static_cast<VkContext*>(h);
    vkWaitForFences(self->device_, 1, &self->frameFences_[self->sync_], VK_TRUE, UINT64_MAX);
}
void VkContext::lockQueue(void* h) { static_cast<VkContext*>(h)->queueMutex_.lock(); }
void VkContext::unlockQueue(void* h) { static_cast<VkContext*>(h)->queueMutex_.unlock(); }
void VkContext::setSignalSemaphore(void* h, VkSemaphore s) { static_cast<VkContext*>(h)->signalSem_ = s; }
