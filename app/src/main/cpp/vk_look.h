#pragma once
// Settings › Video's Picture rows on Vulkan (approved mockup 2026-11-08): the shader stack, Sides of the
// picture, Overlay and colour-blind help as one compute pass (shaders/post_look.comp, look.glsl) that
// writes the whole window into an image VkContext then copies to the swapchain. Two window images take
// turns, so LCD grid's ghosting can read the last frame. Made on first use: nothing runs while every
// row is at its default.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <functional>
#include "display_opts.h"

class VkLook {
public:
    static constexpr uint32_t kFrames = 3; // VkContext's frames in flight

    void setDevice(VkDevice device, const VkPhysicalDeviceMemoryProperties& mem, PFN_vkGetDeviceProcAddr gdpa,
                   std::function<void()> idle);

    // Makes the pipeline (first time) and the window images. False if the pass can't run here.
    bool prepare(uint32_t winW, uint32_t winH) { return winW && winH && ensurePipeline() && ensureImages(winW, winH); }

    // Records the pass (after prepare with the same size): picture = what is shown inside the picture's
    // rectangle (in pictureLayout, read by the compute shader). The window ends in *out, layout GENERAL,
    // ready for the caller's transfer read.
    void record(VkCommandBuffer cmd, uint32_t frame, VkImageView picture, VkImageLayout pictureLayout,
                const Display::LookPush& push, VkImage* out);
    void destroy();

private:
    struct Img { VkImage image = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE; };
    bool ensurePipeline();
    bool ensureImages(uint32_t w, uint32_t h);
    bool makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage, Img& img);
    void freeImage(Img& img);
    void upload(VkCommandBuffer cmd, Display::Image& src, uint32_t& seen, Img& img, VkBuffer& staging, VkDeviceMemory& stagingMem);

    VkDevice device_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mem_{};
    std::function<void()> idle_;
    bool failed_ = false;

    VkSampler sampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkDescriptorSet sets_[kFrames] = {};
    VkShaderModule module_ = VK_NULL_HANDLE;
    VkPipeline pipe_ = VK_NULL_HANDLE;

    uint32_t w_ = 0, h_ = 0, cur_ = 0;
    Img out_[2];
    bool outReady_ = false;        // both window images cleared and in GENERAL
    Img dummy_;                    // 1x1 transparent, for a missing sides or overlay image
    bool dummyReady_ = false;
    Img sides_, overlay_;
    uint32_t sidesSeen_ = 0, overlaySeen_ = 0;
    VkBuffer sidesStaging_ = VK_NULL_HANDLE, overlayStaging_ = VK_NULL_HANDLE;
    VkDeviceMemory sidesStagingMem_ = VK_NULL_HANDLE, overlayStagingMem_ = VK_NULL_HANDLE;

#define VKLOOK_FUNCS(X) X(vkCreateSampler) X(vkDestroySampler) X(vkCreateDescriptorSetLayout) \
    X(vkDestroyDescriptorSetLayout) X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) \
    X(vkCreateDescriptorPool) X(vkDestroyDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
    X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreateComputePipelines) X(vkDestroyPipeline) \
    X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory) \
    X(vkBindImageMemory) X(vkCreateImageView) X(vkDestroyImageView) X(vkCmdBindPipeline) \
    X(vkCmdBindDescriptorSets) X(vkCmdPushConstants) X(vkCmdDispatch) X(vkCmdPipelineBarrier) \
    X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) \
    X(vkMapMemory) X(vkUnmapMemory) X(vkCmdCopyBufferToImage) X(vkCmdClearColorImage)
#define VKLOOK_MEMBER(name) PFN_##name name = nullptr;
    VKLOOK_FUNCS(VKLOOK_MEMBER)
#undef VKLOOK_MEMBER
};
