#include "vk_post.h"
#include "post_consts.h"
#include "joeylog.h"
#include "shaders/post_spv.h"
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

namespace {

// Matches post_common.glsl's push block.
struct Push {
    uint32_t c[16];
    float colour[4];
    float extra[4];
};
static_assert(sizeof(Push) == 96, "push block");

void barrier(PFN_vkCmdPipelineBarrier fn, VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to,
             VkAccessFlags srcA, VkAccessFlags dstA, VkPipelineStageFlags srcS, VkPipelineStageFlags dstS) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from; b.newLayout = to; b.srcAccessMask = srcA; b.dstAccessMask = dstA;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    fn(cmd, srcS, dstS, 0, 0, nullptr, 0, nullptr, 1, &b);
}

} // namespace

void VkPost::setDevice(VkDevice device, const VkPhysicalDeviceMemoryProperties& mem, PFN_vkGetDeviceProcAddr gdpa,
                       std::function<void()> idle) {
    device_ = device; mem_ = mem; idle_ = std::move(idle);
#define VKPOST_LOAD(name) name = reinterpret_cast<PFN_##name>(gdpa(device, #name));
    VKPOST_FUNCS(VKPOST_LOAD)
#undef VKPOST_LOAD
}

bool VkPost::ensurePipelines() {
    if (pipes_[kPlain]) return true;
    if (failed_ || !device_) return false;
    failed_ = true; // until everything below is made

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 0.0f;
    if (vkCreateSampler(device_, &si, nullptr, &sampler_) != VK_SUCCESS) return false;

    VkDescriptorSetLayoutBinding b[2]{};
    b[0].binding = 0; b[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b[0].descriptorCount = 1;
    b[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    b[1].binding = 1; b[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; b[1].descriptorCount = 1;
    b[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dli.bindingCount = 2; dli.pBindings = b;
    if (vkCreateDescriptorSetLayout(device_, &dli, nullptr, &setLayout_) != VK_SUCCESS) return false;

    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1; pli.pSetLayouts = &setLayout_;
    pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pr;
    if (vkCreatePipelineLayout(device_, &pli, nullptr, &layout_) != VK_SUCCESS) return false;

    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kFrames * 2},
                                     {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kFrames * 2}};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = kFrames * 2; dpi.poolSizeCount = 2; dpi.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(device_, &dpi, nullptr, &pool_) != VK_SUCCESS) return false;
    VkDescriptorSetLayout layouts[kFrames * 2];
    std::fill(layouts, layouts + kFrames * 2, setLayout_);
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = pool_; dai.descriptorSetCount = kFrames * 2; dai.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(device_, &dai, &sets_[0][0]) != VK_SUCCESS) return false;

    const uint32_t* code[kPipes] = {k_post_plain_spv, k_post_cas_spv, k_post_easu_spv, k_post_rcas_spv};
    size_t size[kPipes] = {sizeof(k_post_plain_spv), sizeof(k_post_cas_spv), sizeof(k_post_easu_spv), sizeof(k_post_rcas_spv)};
    for (int i = 0; i < kPipes; ++i) {
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        mi.codeSize = size[i]; mi.pCode = code[i];
        if (vkCreateShaderModule(device_, &mi, nullptr, &modules_[i]) != VK_SUCCESS) return false;
        VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; ci.stage.module = modules_[i]; ci.stage.pName = "main";
        ci.layout = layout_;
        if (vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &ci, nullptr, &pipes_[i]) != VK_SUCCESS) {
            LOGE("vulkan: present pass shader %d failed", i);
            pipes_[kPlain] = VK_NULL_HANDLE; // ensurePipelines' "made" check
            return false;
        }
    }
    failed_ = false;
    LOGI("vulkan: colour/sharpening present pass ready");
    return true;
}

bool VkPost::makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage, VkImage& img, VkDeviceMemory& mem, VkImageView& view) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM; ci.extent = {w, h, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &ci, nullptr, &img) != VK_SUCCESS) { img = VK_NULL_HANDLE; return false; }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, img, &req);
    uint32_t type = UINT32_MAX;
    for (uint32_t t = 0; t < mem_.memoryTypeCount && type == UINT32_MAX; ++t)
        if ((req.memoryTypeBits & (1u << t)) && (mem_.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) type = t;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size; mai.memoryTypeIndex = type;
    if (type == UINT32_MAX || vkAllocateMemory(device_, &mai, nullptr, &mem) != VK_SUCCESS) { mem = VK_NULL_HANDLE; return false; }
    vkBindImageMemory(device_, img, mem, 0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = ci.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device_, &vi, nullptr, &view) != VK_SUCCESS) { view = VK_NULL_HANDLE; return false; }
    return true;
}

bool VkPost::ensureImages(uint32_t w, uint32_t h) {
    if (out_ && w == w_ && h == h_) return true;
    if (out_ && idle_) idle_(); // earlier frames may still use them (the window or the shape changed)
    destroyImages();
    if (!makeImage(w, h, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, out_, outMem_, outView_) ||
        !makeImage(w, h, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, mid_, midMem_, midView_)) {
        LOGE("vulkan: present pass images %ux%u failed", w, h);
        destroyImages();
        return false;
    }
    w_ = w; h_ = h;
    LOGI("vulkan: present pass %ux%u", w, h);
    return true;
}

void VkPost::destroyImages() {
    for (auto v : {outView_, midView_}) if (v) vkDestroyImageView(device_, v, nullptr);
    for (auto i : {out_, mid_}) if (i) vkDestroyImage(device_, i, nullptr);
    for (auto m : {outMem_, midMem_}) if (m) vkFreeMemory(device_, m, nullptr);
    outView_ = midView_ = VK_NULL_HANDLE; out_ = mid_ = VK_NULL_HANDLE; outMem_ = midMem_ = VK_NULL_HANDLE;
    w_ = h_ = 0;
}

void VkPost::record(VkCommandBuffer cmd, uint32_t frame, VkImageView src, VkImageLayout srcLayout,
                    uint32_t srcW, uint32_t srcH, uint32_t outW, uint32_t outH, uint32_t sharpK, VkImage* out) {
    int mode = Display::opts().sharpen; // 0 colour only, 1 CAS, 2 FSR
    Push p{};
    float iw = (float)srcW, ih = (float)srcH, ow = (float)outW, oh = (float)outH;
    if (mode == 1) Post::cas(p.c, iw, ih, ow, oh);
    else if (mode == 2) Post::easu(p.c, iw, ih, iw, ih, ow, oh); // the whole image is the picture
    float on = Post::colour(p.colour);
    p.extra[0] = iw; p.extra[1] = ih; p.extra[2] = (float)sharpK; p.extra[3] = on;

    // The previous frame's copy may still read out_, and RCAS mid_: wait for those before writing.
    barrier(vkCmdPipelineBarrier, cmd, out_, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    if (mode == 2)
        barrier(vkCmdPipelineBarrier, cmd, mid_, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    // This frame's sets (its last use has finished: VkContext waited for the frame's fence).
    VkDescriptorImageInfo srcInfo{sampler_, src, srcLayout};
    VkDescriptorImageInfo midIn{sampler_, midView_, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo midOut{VK_NULL_HANDLE, midView_, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo outInfo{VK_NULL_HANDLE, outView_, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet w[4]{};
    for (auto& x : w) { x.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; x.descriptorCount = 1; }
    w[0].dstSet = sets_[frame][0]; w[0].dstBinding = 0; w[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w[0].pImageInfo = &srcInfo;
    w[1].dstSet = sets_[frame][0]; w[1].dstBinding = 1; w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w[1].pImageInfo = mode == 2 ? &midOut : &outInfo;
    w[2].dstSet = sets_[frame][1]; w[2].dstBinding = 0; w[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w[2].pImageInfo = &midIn;
    w[3].dstSet = sets_[frame][1]; w[3].dstBinding = 1; w[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; w[3].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(device_, mode == 2 ? 4 : 2, w, 0, nullptr);

    uint32_t gx = (outW + 7) / 8, gy = (outH + 7) / 8;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[mode == 1 ? kCas : mode == 2 ? kEasu : kPlain]);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &sets_[frame][0], 0, nullptr);
    vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(p), &p);
    vkCmdDispatch(cmd, gx, gy, 1);
    if (mode == 2) {
        barrier(vkCmdPipelineBarrier, cmd, mid_, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        memset(p.c, 0, sizeof(p.c));
        Post::rcas(p.c);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[kRcas]);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &sets_[frame][1], 0, nullptr);
        vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(p), &p);
        vkCmdDispatch(cmd, gx, gy, 1);
    }
    barrier(vkCmdPipelineBarrier, cmd, out_, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT);
    *out = out_;
}

void VkPost::destroy() {
    if (!device_) return;
    destroyImages();
    for (auto& pp : pipes_) if (pp) { vkDestroyPipeline(device_, pp, nullptr); pp = VK_NULL_HANDLE; }
    for (auto& m : modules_) if (m) { vkDestroyShaderModule(device_, m, nullptr); m = VK_NULL_HANDLE; }
    if (pool_) vkDestroyDescriptorPool(device_, pool_, nullptr); // frees sets_
    if (layout_) vkDestroyPipelineLayout(device_, layout_, nullptr);
    if (setLayout_) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
    if (sampler_) vkDestroySampler(device_, sampler_, nullptr);
    pool_ = VK_NULL_HANDLE; layout_ = VK_NULL_HANDLE; setLayout_ = VK_NULL_HANDLE; sampler_ = VK_NULL_HANDLE;
    failed_ = false;
    device_ = VK_NULL_HANDLE;
}
