// X14U 3x 50 MP, stage 2: the viewfinder from the camera-processed PRIVATE companion stream.
//
// In the 50 MP layout (opmode 0x900F, debug.rawr.probe50) the repeating request targets only a PRIVATE companion
// with GPU-sampled usage; no RAW frame reaches the RAW viewfinder. Each companion buffer is imported once as an
// Android external-format Vulkan image (the camera cycles a handful of buffers) and drawn through an immutable YCbCr
// sampler straight into the swapchain, aspect-fit and turned like the RAW viewfinder (present.frag's convention).
#include <android/log.h>

#include <algorithm>
#include <array>
#include <sstream>
#include <stdexcept>
#include <string>

#include "companion_frag.h"
#include "geometry/OrientationTransform.h"
#include "present_vert.h"
#include "presentation/SwapchainRenderer.h"
#include "vulkan/RawImportEvidence.h"

#ifndef VK_QUEUE_FAMILY_FOREIGN_EXT
#define VK_QUEUE_FAMILY_FOREIGN_EXT (~2U)
#endif

namespace rawrcam::presentation {
namespace {
constexpr size_t kMaxCompanionImages = 12;
struct CompanionPush {
    uint32_t quarterTurns;
    float srcAspect;
    float dstAspect;
    float zoom;
};
}  // namespace

SwapchainRenderer::CompanionImage* SwapchainRenderer::findCompanion(AHardwareBuffer* buffer) noexcept {
    for (auto& image : companionImages_)
        if (image.buffer == buffer) return &image;
    return nullptr;
}

void SwapchainRenderer::destroyCompanionPipeline() noexcept {
    if (device_ == VK_NULL_HANDLE) return;
    if (companionPipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, companionPipeline_, nullptr);
    companionPipeline_ = VK_NULL_HANDLE;
    if (companionLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, companionLayout_, nullptr);
    companionLayout_ = VK_NULL_HANDLE;
    if (companionPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device_, companionPool_, nullptr);
    companionPool_ = VK_NULL_HANDLE;
    if (companionSetLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device_, companionSetLayout_, nullptr);
    companionSetLayout_ = VK_NULL_HANDLE;
    if (companionSampler_ != VK_NULL_HANDLE) vkDestroySampler(device_, companionSampler_, nullptr);
    companionSampler_ = VK_NULL_HANDLE;
    if (companionConversion_ != VK_NULL_HANDLE && vkDestroySamplerYcbcrConversion)
        vkDestroySamplerYcbcrConversion(device_, companionConversion_, nullptr);
    companionConversion_ = VK_NULL_HANDLE;
    companionFormat_ = 0;
    companionRenderPass_ = VK_NULL_HANDLE;
}

void SwapchainRenderer::releaseCompanionImages() noexcept {
    if (device_ == VK_NULL_HANDLE) {
        companionImages_.clear();
        return;
    }
    for (auto& image : companionImages_) {
        if (image.view != VK_NULL_HANDLE) vkDestroyImageView(device_, image.view, nullptr);
        if (image.image != VK_NULL_HANDLE) vkDestroyImage(device_, image.image, nullptr);
        // Freeing the imported memory drops Vulkan's reference to the buffer.
        if (image.memory != VK_NULL_HANDLE) vkFreeMemory(device_, image.memory, nullptr);
    }
    companionImages_.clear();
    // The descriptor sets came from companionPool_; start it over so the next session's buffers get fresh sets.
    if (companionPool_ != VK_NULL_HANDLE) vkResetDescriptorPool(device_, companionPool_, 0);
}

bool SwapchainRenderer::ensureCompanionPipeline(uint64_t externalFormat,
                                                const VkAndroidHardwareBufferFormatPropertiesANDROID& format) {
    if (companionPipeline_ != VK_NULL_HANDLE && companionFormat_ == externalFormat && companionRenderPass_ == renderPass_)
        return true;
    // A new format or render pass: the sets are bound to the old immutable sampler.
    releaseCompanionImages();
    destroyCompanionPipeline();
    if (!vkCreateSamplerYcbcrConversion || !context_ || renderPass_ == VK_NULL_HANDLE) return false;

    VkExternalFormatANDROID external{};
    external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    external.externalFormat = externalFormat;
    VkSamplerYcbcrConversionCreateInfo conversion{};
    conversion.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    conversion.pNext = &external;
    conversion.format = VK_FORMAT_UNDEFINED;
    conversion.ycbcrModel = format.suggestedYcbcrModel;
    conversion.ycbcrRange = format.suggestedYcbcrRange;
    conversion.components = format.samplerYcbcrConversionComponents;
    conversion.xChromaOffset = format.suggestedXChromaOffset;
    conversion.yChromaOffset = format.suggestedYChromaOffset;
    const bool linearChroma = (format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT) != 0;
    conversion.chromaFilter = linearChroma ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    if (vkCreateSamplerYcbcrConversion(device_, &conversion, nullptr, &companionConversion_) != VK_SUCCESS) {
        companionConversion_ = VK_NULL_HANDLE;
        return false;
    }
    VkSamplerYcbcrConversionInfo samplerConversion{};
    samplerConversion.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    samplerConversion.conversion = companionConversion_;
    VkSamplerCreateInfo sampler{};
    sampler.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampler.pNext = &samplerConversion;
    // A YCbCr sampler's filters must match the conversion's chroma filter support.
    sampler.magFilter = linearChroma ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    sampler.minFilter = sampler.magFilter;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(device_, &sampler, nullptr, &companionSampler_) != VK_SUCCESS) {
        companionSampler_ = VK_NULL_HANDLE;
        destroyCompanionPipeline();
        return false;
    }
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    binding.pImmutableSamplers = &companionSampler_;
    VkDescriptorSetLayoutCreateInfo setLayout{};
    setLayout.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayout.bindingCount = 1;
    setLayout.pBindings = &binding;
    if (vkCreateDescriptorSetLayout(device_, &setLayout, nullptr, &companionSetLayout_) != VK_SUCCESS) {
        companionSetLayout_ = VK_NULL_HANDLE;
        destroyCompanionPipeline();
        return false;
    }
    // YCbCr images can take several descriptors each (VkSamplerYcbcrConversionImageFormatProperties); be generous.
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, uint32_t(kMaxCompanionImages * 3)};
    VkDescriptorPoolCreateInfo pool{};
    pool.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pool.maxSets = uint32_t(kMaxCompanionImages);
    pool.poolSizeCount = 1;
    pool.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device_, &pool, nullptr, &companionPool_) != VK_SUCCESS) {
        companionPool_ = VK_NULL_HANDLE;
        destroyCompanionPipeline();
        return false;
    }
    VkPushConstantRange push{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(CompanionPush)};
    VkPipelineLayoutCreateInfo layout{};
    layout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout.setLayoutCount = 1;
    layout.pSetLayouts = &companionSetLayout_;
    layout.pushConstantRangeCount = 1;
    layout.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(device_, &layout, nullptr, &companionLayout_) != VK_SUCCESS) {
        companionLayout_ = VK_NULL_HANDLE;
        destroyCompanionPipeline();
        return false;
    }

    VkShaderModule vertex = createShaderModule(present_vert_spv, present_vert_spv_size);
    VkShaderModule fragment = createShaderModule(companion_frag_spv, companion_frag_spv_size);
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertex;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragment;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo assembly{};
    assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkViewport viewport{0, 0, static_cast<float>(swapExtent_.width), static_cast<float>(swapExtent_.height), 0, 1};
    VkRect2D scissor{{0, 0}, swapExtent_};
    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = &viewport;
    viewportState.scissorCount = 1;
    viewportState.pScissors = &scissor;
    const std::array<VkDynamicState, 2> dynamicStates{VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState{};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
    dynamicState.pDynamicStates = dynamicStates.data();
    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;
    VkGraphicsPipelineCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewportState;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamicState;
    info.layout = companionLayout_;
    info.renderPass = renderPass_;
    const VkResult result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &companionPipeline_);
    vkDestroyShaderModule(device_, vertex, nullptr);
    vkDestroyShaderModule(device_, fragment, nullptr);
    if (result != VK_SUCCESS) {
        companionPipeline_ = VK_NULL_HANDLE;
        destroyCompanionPipeline();
        return false;
    }
    companionFormat_ = externalFormat;
    companionRenderPass_ = renderPass_;
    return true;
}

bool SwapchainRenderer::prepareCompanion(AHardwareBuffer* buffer, uint32_t width, uint32_t height) {
    if (!buffer || width == 0 || height == 0 || swapchain_ == VK_NULL_HANDLE || !context_ ||
        !context_->getAhbProperties())
        return false;
    if (auto* known = findCompanion(buffer); known && companionPipeline_ != VK_NULL_HANDLE &&
                                             companionRenderPass_ == renderPass_)
        return true;
    VkAndroidHardwareBufferFormatPropertiesANDROID format{};
    format.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID properties{};
    properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    properties.pNext = &format;
    if (context_->getAhbProperties()(device_, buffer, &properties) != VK_SUCCESS) return false;
    const uint64_t externalFormat = format.externalFormat;
    if (externalFormat == 0 || (format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0) {
        if (!companionLogged_ && diagnostic_)
            diagnostic_("COMPANION_VIEWFINDER_UNSUPPORTED externalFormat=" + std::to_string(externalFormat) +
                        " features=" + std::to_string(format.formatFeatures));
        companionLogged_ = true;
        return false;
    }
    if (!ensureCompanionPipeline(externalFormat, format)) {
        if (!companionLogged_ && diagnostic_) diagnostic_("COMPANION_VIEWFINDER_PIPELINE_FAILED");
        companionLogged_ = true;
        return false;
    }
    if (findCompanion(buffer)) return true;
    if (companionImages_.size() >= kMaxCompanionImages) return false;  // more buffers than the camera should cycle

    CompanionImage entry;
    entry.buffer = buffer;
    entry.width = width;
    entry.height = height;
    VkExternalMemoryImageCreateInfo externalMemory{};
    externalMemory.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalMemory.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkExternalFormatANDROID external{};
    external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    external.pNext = &externalMemory;
    external.externalFormat = externalFormat;
    VkImageCreateInfo image{};
    image.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image.pNext = &external;
    image.imageType = VK_IMAGE_TYPE_2D;
    image.format = VK_FORMAT_UNDEFINED;
    image.extent = {width, height, 1};
    image.mipLevels = 1;
    image.arrayLayers = 1;
    image.samples = VK_SAMPLE_COUNT_1_BIT;
    image.tiling = VK_IMAGE_TILING_OPTIMAL;
    image.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    const auto fail = [&](const char* stage, VkResult result) {
        if (entry.view != VK_NULL_HANDLE) vkDestroyImageView(device_, entry.view, nullptr);
        if (entry.image != VK_NULL_HANDLE) vkDestroyImage(device_, entry.image, nullptr);
        if (entry.memory != VK_NULL_HANDLE) vkFreeMemory(device_, entry.memory, nullptr);
        if (!companionLogged_ && diagnostic_)
            diagnostic_(std::string("COMPANION_VIEWFINDER_IMPORT_FAILED stage=") + stage +
                        " result=" + std::to_string(result));
        companionLogged_ = true;
        return false;
    };
    VkResult result = vkCreateImage(device_, &image, nullptr, &entry.image);
    if (result != VK_SUCCESS) return fail("image", result);
    // Importing takes Vulkan's own reference to the buffer: the image stays valid after the camera's AImage is gone.
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.buffer = buffer;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext = &import;
    dedicated.image = entry.image;
    VkMemoryAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.pNext = &dedicated;
    allocate.allocationSize = properties.allocationSize;
    try {
        allocate.memoryTypeIndex = vulkan::findImportMemoryType(physicalDevice_, properties.memoryTypeBits, 0);
    } catch (const std::exception&) {
        return fail("memory_type", VK_ERROR_FEATURE_NOT_PRESENT);
    }
    result = vkAllocateMemory(device_, &allocate, nullptr, &entry.memory);
    if (result != VK_SUCCESS) return fail("memory", result);
    result = vkBindImageMemory(device_, entry.image, entry.memory, 0);
    if (result != VK_SUCCESS) return fail("bind", result);
    VkSamplerYcbcrConversionInfo viewConversion{};
    viewConversion.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    viewConversion.conversion = companionConversion_;
    VkImageViewCreateInfo view{};
    view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view.pNext = &viewConversion;
    view.image = entry.image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = VK_FORMAT_UNDEFINED;
    view.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                       VK_COMPONENT_SWIZZLE_IDENTITY};
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    result = vkCreateImageView(device_, &view, nullptr, &entry.view);
    if (result != VK_SUCCESS) return fail("view", result);
    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = companionPool_;
    setInfo.descriptorSetCount = 1;
    setInfo.pSetLayouts = &companionSetLayout_;
    result = vkAllocateDescriptorSets(device_, &setInfo, &entry.set);
    if (result != VK_SUCCESS) return fail("set", result);
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = entry.view;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = entry.set;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    if (companionImages_.empty() && diagnostic_) {
        std::ostringstream line;
        line << "COMPANION_VIEWFINDER_READY size=" << width << 'x' << height << " externalFormat=" << externalFormat
             << " model=" << int(format.suggestedYcbcrModel) << " range=" << int(format.suggestedYcbcrRange)
             << " linearChroma="
             << ((format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT) ? 1 : 0)
             << " swap=" << swapExtent_.width << 'x' << swapExtent_.height;
        diagnostic_(line.str());
    }
    companionImages_.push_back(entry);
    return true;
}

void SwapchainRenderer::recordCompanion(VkCommandBuffer command, uint32_t imageIndex, AHardwareBuffer* buffer,
                                        int sensorOrientationDegrees, int displayRotationDegrees, float zoom) {
    CompanionImage* source = findCompanion(buffer);
    if (!source || imageIndex >= swapImages_.size() || companionPipeline_ == VK_NULL_HANDLE)
        throw std::logic_error("recordCompanion without a prepared companion buffer");
    std::array<VkImageMemoryBarrier, 2> barriers{};
    // The camera wrote the buffer outside Vulkan; acquire it from the foreign queue family.
    barriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    // GENERAL both ways, like the RAW AHB import: an UNDEFINED old layout would let the driver discard the picture.
    barriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    barriers[0].dstQueueFamilyIndex = context_->queueFamily();
    barriers[0].image = source->image;
    barriers[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[1].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    barriers[1].oldLayout = swapInitialized_[imageIndex] ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[1].newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    barriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].image = swapImages_[imageIndex];
    barriers[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0,
                         nullptr, 0, nullptr, uint32_t(barriers.size()), barriers.data());
    VkClearValue clear{};
    clear.color = {{0, 0, 0, 1}};
    VkRenderPassBeginInfo pass{};
    pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    pass.renderPass = renderPass_;
    pass.framebuffer = framebuffers_[imageIndex];
    pass.renderArea = {{0, 0}, swapExtent_};
    pass.clearValueCount = 1;
    pass.pClearValues = &clear;
    vkCmdBeginRenderPass(command, &pass, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, companionPipeline_);
    VkViewport full{0, 0, static_cast<float>(swapExtent_.width), static_cast<float>(swapExtent_.height), 0, 1};
    VkRect2D fullScissor{{0, 0}, swapExtent_};
    vkCmdSetViewport(command, 0, 1, &full);
    vkCmdSetScissor(command, 0, 1, &fullScissor);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, companionLayout_, 0, 1, &source->set, 0, nullptr);
    const uint32_t turns = rawrcam::geometry::presentationQuarterTurns(sensorOrientationDegrees, displayRotationDegrees);
    const bool quarter = (turns & 1u) != 0;
    const float sw = float(quarter ? source->height : source->width), sh = float(quarter ? source->width : source->height);
    const CompanionPush push{turns, sw / sh, float(swapExtent_.width) / float(swapExtent_.height), zoom};
    vkCmdPushConstants(command, companionLayout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);
    vkCmdDraw(command, 3, 1, 0, 0);
    vkCmdEndRenderPass(command);
    // Hand the buffer back to the camera.
    VkImageMemoryBarrier release = barriers[0];
    release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    release.dstAccessMask = 0;
    release.srcQueueFamilyIndex = context_->queueFamily();
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &release);
    swapInitialized_[imageIndex] = true;
}

}  // namespace rawrcam::presentation
