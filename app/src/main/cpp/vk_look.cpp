#include "vk_look.h"
#include "joeylog.h"
#include "shaders/post_spv.h"
#include <android/log.h>

#include <algorithm>
#include <cstring>

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

namespace {
const VkImageSubresourceRange kWhole{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

void barrier(PFN_vkCmdPipelineBarrier fn, VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to,
             VkAccessFlags srcA, VkAccessFlags dstA, VkPipelineStageFlags srcS, VkPipelineStageFlags dstS) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from; b.newLayout = to; b.srcAccessMask = srcA; b.dstAccessMask = dstA;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img; b.subresourceRange = kWhole;
    fn(cmd, srcS, dstS, 0, 0, nullptr, 0, nullptr, 1, &b);
}
} // namespace

void VkLook::setDevice(VkDevice device, const VkPhysicalDeviceMemoryProperties& mem, PFN_vkGetDeviceProcAddr gdpa,
                       std::function<void()> idle) {
    device_ = device; mem_ = mem; idle_ = std::move(idle);
#define VKLOOK_LOAD(name) name = reinterpret_cast<PFN_##name>(gdpa(device, #name));
    VKLOOK_FUNCS(VKLOOK_LOAD)
#undef VKLOOK_LOAD
}

bool VkLook::ensurePipeline() {
    if (pipe_) return true;
    if (failed_ || !device_) return false;
    failed_ = true; // until everything below is made

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(device_, &si, nullptr, &sampler_) != VK_SUCCESS) return false;

    VkDescriptorSetLayoutBinding b[5]{};
    for (uint32_t i = 0; i < 5; ++i) {
        b[i].binding = i; b[i].descriptorCount = 1; b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].descriptorType = i == 1 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dli.bindingCount = 5; dli.pBindings = b;
    if (vkCreateDescriptorSetLayout(device_, &dli, nullptr, &setLayout_) != VK_SUCCESS) return false;

    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Display::LookPush)};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1; pli.pSetLayouts = &setLayout_;
    pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pr;
    if (vkCreatePipelineLayout(device_, &pli, nullptr, &layout_) != VK_SUCCESS) return false;

    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kFrames * 4},
                                     {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kFrames}};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = kFrames; dpi.poolSizeCount = 2; dpi.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(device_, &dpi, nullptr, &pool_) != VK_SUCCESS) return false;
    VkDescriptorSetLayout layouts[kFrames];
    std::fill(layouts, layouts + kFrames, setLayout_);
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = pool_; dai.descriptorSetCount = kFrames; dai.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(device_, &dai, sets_) != VK_SUCCESS) return false;

    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = sizeof(k_post_look_spv); mi.pCode = k_post_look_spv;
    if (vkCreateShaderModule(device_, &mi, nullptr, &module_) != VK_SUCCESS) return false;
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; ci.stage.module = module_; ci.stage.pName = "main";
    ci.layout = layout_;
    if (vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &ci, nullptr, &pipe_) != VK_SUCCESS) {
        LOGE("vulkan: picture pass shader failed");
        pipe_ = VK_NULL_HANDLE;
        return false;
    }
    if (!makeImage(1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, dummy_)) return false;
    failed_ = false;
    LOGI("vulkan: picture pass (shader, sides, overlay) ready");
    return true;
}

bool VkLook::makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage, Img& img) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM; ci.extent = {w, h, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &ci, nullptr, &img.image) != VK_SUCCESS) { img.image = VK_NULL_HANDLE; return false; }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, img.image, &req);
    uint32_t type = UINT32_MAX;
    for (uint32_t t = 0; t < mem_.memoryTypeCount && type == UINT32_MAX; ++t)
        if ((req.memoryTypeBits & (1u << t)) && (mem_.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) type = t;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size; mai.memoryTypeIndex = type;
    if (type == UINT32_MAX || vkAllocateMemory(device_, &mai, nullptr, &img.mem) != VK_SUCCESS) { img.mem = VK_NULL_HANDLE; freeImage(img); return false; }
    vkBindImageMemory(device_, img.image, img.mem, 0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = img.image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = ci.format; vi.subresourceRange = kWhole;
    if (vkCreateImageView(device_, &vi, nullptr, &img.view) != VK_SUCCESS) { img.view = VK_NULL_HANDLE; freeImage(img); return false; }
    return true;
}

void VkLook::freeImage(Img& img) {
    if (img.view) vkDestroyImageView(device_, img.view, nullptr);
    if (img.image) vkDestroyImage(device_, img.image, nullptr);
    if (img.mem) vkFreeMemory(device_, img.mem, nullptr);
    img = Img{};
}

bool VkLook::ensureImages(uint32_t w, uint32_t h) {
    if (out_[0].image && w == w_ && h == h_) return true;
    if (out_[0].image && idle_) idle_(); // earlier frames may still use them (the window changed)
    freeImage(out_[0]); freeImage(out_[1]);
    outReady_ = false; w_ = h_ = 0;
    VkImageUsageFlags u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                          VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (!makeImage(w, h, u, out_[0]) || !makeImage(w, h, u, out_[1])) {
        LOGE("vulkan: picture pass images %ux%u failed", w, h);
        freeImage(out_[0]); freeImage(out_[1]);
        return false;
    }
    w_ = w; h_ = h;
    LOGI("vulkan: picture pass %ux%u", w, h);
    return true;
}

// A new sides or overlay image from the UI thread: copied through a staging buffer in this frame's commands.
void VkLook::upload(VkCommandBuffer cmd, Display::Image& src, uint32_t& seen, Img& img, VkBuffer& staging, VkDeviceMemory& stagingMem) {
    uint32_t gen = src.gen;
    if (gen == seen) return;
    seen = gen;
    std::vector<uint8_t> px;
    int w, h;
    { std::lock_guard<std::mutex> l(src.m); px = src.rgba; w = src.w; h = src.h; }
    if (img.image || staging) { if (idle_) idle_(); }
    freeImage(img);
    if (staging) { vkDestroyBuffer(device_, staging, nullptr); staging = VK_NULL_HANDLE; }
    if (stagingMem) { vkFreeMemory(device_, stagingMem, nullptr); stagingMem = VK_NULL_HANDLE; }
    if (px.empty() || w <= 0 || h <= 0) return;
    if (!makeImage((uint32_t)w, (uint32_t)h, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, img)) return;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = px.size(); bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT; bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device_, &bi, nullptr, &staging) != VK_SUCCESS) { staging = VK_NULL_HANDLE; freeImage(img); return; }
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, staging, &req);
    uint32_t type = UINT32_MAX;
    const VkMemoryPropertyFlags want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t t = 0; t < mem_.memoryTypeCount && type == UINT32_MAX; ++t)
        if ((req.memoryTypeBits & (1u << t)) && (mem_.memoryTypes[t].propertyFlags & want) == want) type = t;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size; mai.memoryTypeIndex = type;
    void* map = nullptr;
    if (type == UINT32_MAX || vkAllocateMemory(device_, &mai, nullptr, &stagingMem) != VK_SUCCESS ||
        vkBindBufferMemory(device_, staging, stagingMem, 0) != VK_SUCCESS ||
        vkMapMemory(device_, stagingMem, 0, VK_WHOLE_SIZE, 0, &map) != VK_SUCCESS) {
        LOGE("vulkan: picture pass: can't upload a %dx%d image", w, h);
        freeImage(img);
        return;
    }
    memcpy(map, px.data(), px.size());
    vkUnmapMemory(device_, stagingMem);
    barrier(vkCmdPipelineBarrier, cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy r{};
    r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.imageExtent = {(uint32_t)w, (uint32_t)h, 1};
    vkCmdCopyBufferToImage(cmd, staging, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    barrier(vkCmdPipelineBarrier, cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    LOGI("vulkan: picture pass image %dx%d", w, h);
}

void VkLook::record(VkCommandBuffer cmd, uint32_t frame, VkImageView picture, VkImageLayout pictureLayout,
                    const Display::LookPush& push, VkImage* out) {
    VkClearColorValue clear{};
    if (!dummyReady_) {
        barrier(vkCmdPipelineBarrier, cmd, dummy_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        vkCmdClearColorImage(cmd, dummy_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &kWhole);
        barrier(vkCmdPipelineBarrier, cmd, dummy_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        dummyReady_ = true;
    }
    if (!outReady_) { // both window images: cleared once, then always GENERAL (written, sampled and copied from)
        for (auto& o : out_) {
            barrier(vkCmdPipelineBarrier, cmd, o.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            vkCmdClearColorImage(cmd, o.image, VK_IMAGE_LAYOUT_GENERAL, &clear, 1, &kWhole);
            barrier(vkCmdPipelineBarrier, cmd, o.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        outReady_ = true;
    }
    upload(cmd, Display::sidesImage(), sidesSeen_, sides_, sidesStaging_, sidesStagingMem_);
    upload(cmd, Display::overlayImage(), overlaySeen_, overlay_, overlayStaging_, overlayStagingMem_);

    Img& dst = out_[cur_];
    Img& prev = out_[cur_ ^ 1];
    // The copy two frames ago still read dst: wait for it before writing; the last frame's writes to prev are read here.
    barrier(vkCmdPipelineBarrier, cmd, dst.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkDescriptorImageInfo pic{sampler_, picture, pictureLayout};
    VkDescriptorImageInfo dstInfo{VK_NULL_HANDLE, dst.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo sidesInfo{sampler_, sides_.view ? sides_.view : dummy_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo ovInfo{sampler_, overlay_.view ? overlay_.view : dummy_.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo prevInfo{sampler_, prev.view, VK_IMAGE_LAYOUT_GENERAL};
    const VkDescriptorImageInfo* infos[5] = {&pic, &dstInfo, &sidesInfo, &ovInfo, &prevInfo};
    VkWriteDescriptorSet w[5]{};
    for (uint32_t i = 0; i < 5; ++i) {
        w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; w[i].dstSet = sets_[frame]; w[i].dstBinding = i;
        w[i].descriptorCount = 1; w[i].pImageInfo = infos[i];
        w[i].descriptorType = i == 1 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }
    vkUpdateDescriptorSets(device_, 5, w, 0, nullptr);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &sets_[frame], 0, nullptr);
    vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, (w_ + 7) / 8, (h_ + 7) / 8, 1);
    barrier(vkCmdPipelineBarrier, cmd, dst.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    *out = dst.image;
    cur_ ^= 1;
}

void VkLook::destroy() {
    if (!device_) return;
    freeImage(out_[0]); freeImage(out_[1]); freeImage(dummy_); freeImage(sides_); freeImage(overlay_);
    for (VkBuffer* b : {&sidesStaging_, &overlayStaging_}) if (*b) { vkDestroyBuffer(device_, *b, nullptr); *b = VK_NULL_HANDLE; }
    for (VkDeviceMemory* m : {&sidesStagingMem_, &overlayStagingMem_}) if (*m) { vkFreeMemory(device_, *m, nullptr); *m = VK_NULL_HANDLE; }
    if (pipe_) vkDestroyPipeline(device_, pipe_, nullptr);
    if (module_) vkDestroyShaderModule(device_, module_, nullptr);
    if (pool_) vkDestroyDescriptorPool(device_, pool_, nullptr);
    if (layout_) vkDestroyPipelineLayout(device_, layout_, nullptr);
    if (setLayout_) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
    if (sampler_) vkDestroySampler(device_, sampler_, nullptr);
    pipe_ = VK_NULL_HANDLE; module_ = VK_NULL_HANDLE; pool_ = VK_NULL_HANDLE; layout_ = VK_NULL_HANDLE;
    setLayout_ = VK_NULL_HANDLE; sampler_ = VK_NULL_HANDLE;
    outReady_ = dummyReady_ = false; w_ = h_ = 0; cur_ = 0;
    sidesSeen_ = overlaySeen_ = 0; // a new device uploads the images again
    failed_ = false;
    device_ = VK_NULL_HANDLE;
}
