// X14U 3x 50 MP, stage 2: the probe layout's viewfinder.
//
// With debug.rawr.probe50 the repeating request targets only the camera-processed PRIVATE companion, so no RAW
// frame ever reaches the RAW viewfinder. CameraDeviceSession hands each newest companion frame here (listener
// thread); it is drawn straight into the swapchain (SwapchainRenderer::recordCompanion) and kept until its GPU
// work is done. Two frames in flight at most; a frame that finds both slots busy is dropped.
#include <android/hardware_buffer.h>

#include <chrono>
#include <string>

#include "diagnostics/logging/NativeLog.h"
#include "session/SessionEngine.h"
#include "vulkan/VulkanDispatch.h"

namespace rawrcam::session {
namespace {
constexpr uint64_t kSlotWaitNs = 50'000'000;  // a busy slot past this drops the new frame instead
}  // namespace

bool SessionEngine::ensureCompanionPresentLocked() {
    if (companion_.pool != VK_NULL_HANDLE) return true;
    const VkDevice device = vulkanContext_.device();
    if (device == VK_NULL_HANDLE) return false;
    VkCommandPoolCreateInfo pool{};
    pool.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = vulkanContext_.queueFamily();
    if (vkCreateCommandPool(device, &pool, nullptr, &companion_.pool) != VK_SUCCESS) {
        companion_.pool = VK_NULL_HANDLE;
        return false;
    }
    VkCommandBufferAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate.commandPool = companion_.pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = uint32_t(companion_.commands.size());
    bool ok = vkAllocateCommandBuffers(device, &allocate, companion_.commands.data()) == VK_SUCCESS;
    VkSemaphoreCreateInfo semaphore{};
    semaphore.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fence{};
    fence.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    for (size_t i = 0; ok && i < companion_.fences.size(); ++i) {
        ok = vkCreateSemaphore(device, &semaphore, nullptr, &companion_.acquired[i]) == VK_SUCCESS &&
             vkCreateSemaphore(device, &semaphore, nullptr, &companion_.rendered[i]) == VK_SUCCESS &&
             vkCreateFence(device, &fence, nullptr, &companion_.fences[i]) == VK_SUCCESS;
    }
    if (!ok) {
        destroyCompanionPresentLocked();
        appendDiagnostic("COMPANION_VIEWFINDER_SETUP_FAILED");
        return false;
    }
    return true;
}

void SessionEngine::destroyCompanionPresentLocked() noexcept {
    const VkDevice device = vulkanContext_.device();
    if (device == VK_NULL_HANDLE) {
        companion_ = {};
        return;
    }
    for (size_t i = 0; i < companion_.fences.size(); ++i) {
        if (companion_.submitted[i] && companion_.fences[i] != VK_NULL_HANDLE)
            (void)vkWaitForFences(device, 1, &companion_.fences[i], VK_TRUE, 1'000'000'000);
        if (companion_.held[i]) AImage_delete(companion_.held[i]);
        if (companion_.fences[i] != VK_NULL_HANDLE) vkDestroyFence(device, companion_.fences[i], nullptr);
        if (companion_.acquired[i] != VK_NULL_HANDLE) vkDestroySemaphore(device, companion_.acquired[i], nullptr);
        if (companion_.rendered[i] != VK_NULL_HANDLE) vkDestroySemaphore(device, companion_.rendered[i], nullptr);
    }
    if (companion_.pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, companion_.pool, nullptr);
    companion_ = {};
}

void SessionEngine::releaseCompanionFramesLocked() {
    const VkDevice device = vulkanContext_.device();
    for (size_t i = 0; i < companion_.held.size(); ++i) {
        if (companion_.submitted[i] && device != VK_NULL_HANDLE)
            (void)vkWaitForFences(device, 1, &companion_.fences[i], VK_TRUE, 1'000'000'000);
        companion_.submitted[i] = false;
        if (companion_.held[i]) AImage_delete(companion_.held[i]);
        companion_.held[i] = nullptr;
    }
    // The next session's buffers are new ones; the imported images go with the old.
    swapchainRenderer_.releaseCompanionImages();
    if (companion_.frames)
        appendDiagnostic("COMPANION_VIEWFINDER_STOP frames=" + std::to_string(companion_.frames) +
                         " skipped=" + std::to_string(companion_.skipped));
    companion_.frames = companion_.skipped = 0;
}

void SessionEngine::releaseCompanionFrames() {
    std::lock_guard<std::mutex> lock(mu_);
    releaseCompanionFramesLocked();
}

void SessionEngine::onCompanionFrame(AImage* image) {
    if (!image) return;
    std::lock_guard<std::mutex> lock(mu_);
    const auto skip = [&] {
        AImage_delete(image);
        ++companion_.skipped;
    };
    const VkDevice device = vulkanContext_.device();
    if (device == VK_NULL_HANDLE || !swapchainRenderer_.ready() || !ensureCompanionPresentLocked()) return skip();
    const uint32_t slot = companion_.next;
    if (companion_.submitted[slot]) {
        if (vkWaitForFences(device, 1, &companion_.fences[slot], VK_TRUE, kSlotWaitNs) != VK_SUCCESS) return skip();
        companion_.submitted[slot] = false;
    }
    if (companion_.held[slot]) {
        AImage_delete(companion_.held[slot]);
        companion_.held[slot] = nullptr;
    }
    AHardwareBuffer* buffer = nullptr;
    int32_t width = 0, height = 0;
    if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK || !buffer || AImage_getWidth(image, &width) != AMEDIA_OK ||
        AImage_getHeight(image, &height) != AMEDIA_OK || width <= 0 || height <= 0)
        return skip();
    try {
        if (!swapchainRenderer_.prepareCompanion(buffer, uint32_t(width), uint32_t(height))) return skip();
        uint32_t index = 0;
        const VkResult acquire = swapchainRenderer_.acquireNextImage(companion_.acquired[slot], &index);
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) return skip();
        VkCommandBuffer command = companion_.commands[slot];
        (void)vkResetCommandBuffer(command, 0);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(command, &begin);
        swapchainRenderer_.recordCompanion(command, index, buffer, realtime_.sensorOrientationDegrees(),
                                           realtime_.displayRotationDegrees());
        vkEndCommandBuffer(command);
        (void)vkResetFences(device, 1, &companion_.fences[slot]);
        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &companion_.acquired[slot];
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &companion_.rendered[slot];
        {
            std::lock_guard<std::mutex> queueLock(queueSubmitMutex_);
            const VkResult submitted = vkQueueSubmit(vulkanContext_.queue(), 1, &submit, companion_.fences[slot]);
            if (submitted != VK_SUCCESS) {
                appendDiagnostic("COMPANION_VIEWFINDER_SUBMIT_FAILED result=" + std::to_string(submitted));
                return skip();
            }
            companion_.submitted[slot] = true;
            (void)swapchainRenderer_.present(vulkanContext_.queue(), companion_.rendered[slot], index);
        }
    } catch (const std::exception& e) {
        appendDiagnostic(std::string("COMPANION_VIEWFINDER_FAILED ") + e.what());
        return skip();
    }
    companion_.held[slot] = image;
    companion_.next = slot ^ 1u;
    // A drawn companion frame is the live preview: after a 50 MP still the UI waits for one newer than the still
    // ("Restoring preview…"), which in this layout never comes from the RAW pipeline.
    int64_t timestampNs = 0;
    if (lastCameraContext_ && AImage_getTimestamp(image, &timestampNs) == AMEDIA_OK && timestampNs > 0)
        swapchainRenderer_.noteLivePresentation(lastCameraContext_->cameraContextGeneration, timestampNs);
    if (++companion_.frames == 1)
        appendDiagnostic("COMPANION_VIEWFINDER_START size=" + std::to_string(width) + "x" + std::to_string(height) +
                         " sensorOrientation=" + std::to_string(realtime_.sensorOrientationDegrees()) +
                         " displayRotation=" + std::to_string(realtime_.displayRotationDegrees()));
}

}  // namespace rawrcam::session
