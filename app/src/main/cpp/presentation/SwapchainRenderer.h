#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <vector>
#include <utility>

#include "geometry/LensDistortion.h"
#include "imaging/FrameLimits.h"
#include "monitoring/MonitoringState.h"
#include "presentation/PresentRecorder.h"
#include "vulkan/VulkanContext.h"

namespace rawrcam::presentation {

class SwapchainRenderer final {
   public:
    using Diagnostic = std::function<void(const std::string&)>;

    explicit SwapchainRenderer(Diagnostic diagnostic);
    ~SwapchainRenderer();

    SwapchainRenderer(const SwapchainRenderer&) = delete;
    SwapchainRenderer& operator=(const SwapchainRenderer&) = delete;

    void initialize(const vulkan::VulkanContext& context);
    void createSwapchain(VkSurfaceKHR surface, ANativeWindow* window);
    void destroySwapchain();

    void setFrameSources(
        const std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight>& tonemappedViews,
        const std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight>& linearViews,
        const std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight>& overlayViews,
        const std::array<std::array<VkImageView, 3>, rawrcam::imaging::kRealtimeFramesInFlight>& scopeViews,
        float scopeSourceAspect);
    void setVideoFrameSources(const std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight>& views);
    // Recording: draw the scopes measured in another frame slot (they update less often than the frames); -1 = own.
    void setScopeSourceSlot(int slot) noexcept { scopeSourceSlot_ = slot; }
    void setScopePresentationState(const rawrcam::monitoring::ScopePresentationState& state) noexcept {
        scopeState_ = state;
    }
    void clearFrameSources() noexcept;
    /** X14U focus loupe (see PresentRecorder LoupeDraw); disabled = none. */
    void setLoupe(const LoupeDraw& loupe) noexcept {
        std::lock_guard<std::mutex> lock(loupeMutex_);
        loupe_ = loupe;
    }

    [[nodiscard]] bool ready() const noexcept { return swapchain_ != VK_NULL_HANDLE; }
    [[nodiscard]] VkExtent2D extent() const noexcept { return swapExtent_; }

    VkResult acquireNextImage(VkSemaphore imageAvailable, uint32_t* imageIndex) const;
    VkResult present(VkQueue queue, VkSemaphore renderFinished, uint32_t imageIndex) const;

    void record(VkCommandBuffer command, uint32_t frameSlot, uint32_t imageIndex, uint32_t previewWidth,
                uint32_t previewHeight, int sensorOrientationDegrees, int displayRotationDegrees,
                uint32_t diagnosticMode, bool monitoringOverlayEnabled, uint32_t exifOrientation = 0,
                bool videoSource = false, uint32_t cropX = 0, uint32_t cropY = 0, uint32_t cropW = 0,
                uint32_t cropH = 0, const rawrcam::geometry::LensDistortionModel* lensWarp = nullptr,
                uint32_t lensMirror = 0);

    // Updated only after a fresh live RAW frame is accepted for presentation.
    void noteLivePresentation(uint64_t generation, int64_t timestamp) {
        std::lock_guard<std::mutex> lock(lensWarpMutex_);
        livePresentation_ = {generation, timestamp};
    }
    std::pair<uint64_t, int64_t> livePresentation() const {
        std::lock_guard<std::mutex> lock(lensWarpMutex_);
        return livePresentation_;
    }
    void resetLogging() noexcept { recorder_.resetLogging(); }

    // X14U 3x 50 MP (stage 2): the viewfinder of a session whose repeating request targets only the camera-processed
    // PRIVATE companion (GPU-sampled usage). prepareCompanion imports the buffer (cached per buffer; the camera
    // cycles a few) and builds the YCbCr sampler/pipeline for its format; false = not drawable, skip the frame.
    // recordCompanion then draws it into an acquired swapchain image, aspect-fit and turned like the RAW viewfinder.
    bool prepareCompanion(AHardwareBuffer* buffer, uint32_t width, uint32_t height);
    void recordCompanion(VkCommandBuffer command, uint32_t imageIndex, AHardwareBuffer* buffer,
                         int sensorOrientationDegrees, int displayRotationDegrees);
    // Drops every imported companion buffer. The caller has waited for the GPU work that sampled them.
    void releaseCompanionImages() noexcept;
    // Lens calibration the last live-viewfinder frame was undistorted with
    // (nullopt = none), so display taps and face boxes map through it.
    [[nodiscard]] std::optional<rawrcam::geometry::LensDistortionModel> presentedLensWarp() const {
        std::lock_guard<std::mutex> lock(lensWarpMutex_);
        return presentedLensWarp_;
    }
    // Lens profile mirror the last live-viewfinder frame was flipped with.
    [[nodiscard]] uint32_t presentedLensMirror() const {
        std::lock_guard<std::mutex> lock(lensWarpMutex_);
        return presentedLensMirror_;
    }

   private:
    struct PresentPush {
        uint32_t quarterTurns;
        float srcAspect;
        float dstAspect;
        uint32_t diagnosticMode;
        uint32_t overlayEnabled;
        float cornerRadius;
        uint32_t exifOrientation;
        uint32_t cropEnabled;
        float cropX0;
        float cropY0;
        float cropX1;
        float cropY1;
        uint32_t lensWarp;
        float lensCx, lensCy, lensFx, lensFy, lensFxOut, lensFyOut;
        std::array<float, 8> k;
        uint32_t lensMirror;
        float rollCos, rollSin;
    };

    std::pair<uint64_t, int64_t> livePresentation_{};
    mutable std::mutex lensWarpMutex_;
    std::optional<rawrcam::geometry::LensDistortionModel> presentedLensWarp_;
    uint32_t presentedLensMirror_ = 0;

    struct CompanionImage {
        AHardwareBuffer* buffer = nullptr;
        uint32_t width = 0, height = 0;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDescriptorSet set = VK_NULL_HANDLE;
        bool initialized = false;
    };
    CompanionImage* findCompanion(AHardwareBuffer* buffer) noexcept;
    bool ensureCompanionPipeline(uint64_t externalFormat, const VkAndroidHardwareBufferFormatPropertiesANDROID& format);
    void destroyCompanionPipeline() noexcept;
    std::vector<CompanionImage> companionImages_;
    uint64_t companionFormat_ = 0;
    VkRenderPass companionRenderPass_ = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion companionConversion_ = VK_NULL_HANDLE;
    VkSampler companionSampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout companionSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout companionLayout_ = VK_NULL_HANDLE;
    VkPipeline companionPipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool companionPool_ = VK_NULL_HANDLE;
    bool companionLogged_ = false;

    void createPipeline();
    void createFramebuffers();
    void rebuildDescriptors();
    VkShaderModule createShaderModule(const unsigned char* bytes, size_t size) const;

    Diagnostic diagnostic_;
    PresentRecorder recorder_;
    const vulkan::VulkanContext* context_ = nullptr;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkSurfaceFormatKHR surfaceFormat_{};
    VkExtent2D swapExtent_{};
    std::vector<VkImage> swapImages_;
    std::vector<VkImageView> swapViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<bool> swapInitialized_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkPipeline scopePipeline_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, rawrcam::imaging::kRealtimeFramesInFlight> tonemappedSets_{};
    std::array<VkDescriptorSet, rawrcam::imaging::kRealtimeFramesInFlight> linearSets_{};
    std::array<VkDescriptorSet, rawrcam::imaging::kRealtimeFramesInFlight> videoSets_{};
    std::array<std::array<VkDescriptorSet, 3>, rawrcam::imaging::kRealtimeFramesInFlight> scopeSets_{};
    std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight> tonemappedViews_{};
    std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight> linearViews_{};
    std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight> videoViews_{};
    std::array<VkImageView, rawrcam::imaging::kRealtimeFramesInFlight> overlayViews_{};
    std::array<std::array<VkImageView, 3>, rawrcam::imaging::kRealtimeFramesInFlight> scopeViews_{};
    rawrcam::monitoring::ScopePresentationState scopeState_{};
    mutable std::mutex loupeMutex_;
    LoupeDraw loupe_{};
    float scopeSourceAspect_ = 1.0f;
    int scopeSourceSlot_ = -1;
    bool hasFrameSources_ = false;
    bool hasVideoFrameSources_ = false;
};

}  // namespace rawrcam::presentation
