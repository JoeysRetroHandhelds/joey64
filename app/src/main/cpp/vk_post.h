#pragma once
// Settings › Video's present pass on Vulkan: colour (brightness, contrast, saturation, gamma) and
// sharpening (AMD CAS, or FSR 1 EASU + RCAS) as compute shaders from the core's picture into an image
// at the size it is shown, which VkContext then copies into the swapchain image. Made on first use,
// so it costs nothing while every setting is at its default.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <functional>

class VkPost {
public:
    static constexpr uint32_t kFrames = 3; // VkContext's frames in flight (descriptor sets per frame)

    // gdpa loads the device calls; idle waits for the queue (images are remade when the size changes).
    void setDevice(VkDevice device, const VkPhysicalDeviceMemoryProperties& mem, PFN_vkGetDeviceProcAddr gdpa,
                   std::function<void()> idle);

    // Before the frame's barriers: makes the pipelines (first time) and the outW x outH images.
    // False if the pass can't run here (the caller blits as before).
    bool prepare(uint32_t outW, uint32_t outH) { return outW && outH && ensurePipelines() && ensureImages(outW, outH); }

    // Records the pass into cmd (after prepare with the same size): src (the core's view, in srcLayout, sampled by the compute shader)
    // at srcW x srcH to an outW x outH picture, left in TRANSFER_SRC_OPTIMAL in *out for the caller's
    // copy. frame = the sync index (its previous use has finished). sharpK > 1: sharp-bilinear
    // (colour only).
    void record(VkCommandBuffer cmd, uint32_t frame, VkImageView src, VkImageLayout srcLayout,
                uint32_t srcW, uint32_t srcH, uint32_t outW, uint32_t outH, uint32_t sharpK, VkImage* out);
    void destroy();

private:
    bool ensurePipelines();
    bool ensureImages(uint32_t w, uint32_t h);
    void destroyImages();
    bool makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage, VkImage& img, VkDeviceMemory& mem, VkImageView& view);

    VkDevice device_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mem_{};
    std::function<void()> idle_;
    bool failed_ = false;

    VkSampler sampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkDescriptorSet sets_[kFrames][2] = {};
    enum { kPlain, kCas, kEasu, kRcas, kPipes };
    VkShaderModule modules_[kPipes] = {};
    VkPipeline pipes_[kPipes] = {};

    // out: what is copied to the screen; mid: FSR's EASU result, read by RCAS.
    uint32_t w_ = 0, h_ = 0;
    VkImage out_ = VK_NULL_HANDLE, mid_ = VK_NULL_HANDLE;
    VkDeviceMemory outMem_ = VK_NULL_HANDLE, midMem_ = VK_NULL_HANDLE;
    VkImageView outView_ = VK_NULL_HANDLE, midView_ = VK_NULL_HANDLE;

#define VKPOST_FUNCS(X) X(vkCreateSampler) X(vkDestroySampler) X(vkCreateDescriptorSetLayout) \
    X(vkDestroyDescriptorSetLayout) X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) \
    X(vkCreateDescriptorPool) X(vkDestroyDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
    X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreateComputePipelines) X(vkDestroyPipeline) \
    X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory) \
    X(vkBindImageMemory) X(vkCreateImageView) X(vkDestroyImageView) X(vkCmdBindPipeline) \
    X(vkCmdBindDescriptorSets) X(vkCmdPushConstants) X(vkCmdDispatch) X(vkCmdPipelineBarrier)
#define VKPOST_MEMBER(name) PFN_##name name = nullptr;
    VKPOST_FUNCS(VKPOST_MEMBER)
#undef VKPOST_MEMBER
};
