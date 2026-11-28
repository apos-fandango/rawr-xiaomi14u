// Tone/LUT/color-mode controls.
#include <algorithm>
#include "diagnostics/logging/NativeLog.h"
#include "session/SessionEngine.h"
#include "tonemap/ColorRenderProfile.h"

namespace rawrcam::session {

void SessionEngine::setAssetManager(AAssetManager* assetManager) {
    std::lock_guard<std::mutex> lock(mu_);
    replayAssetManager_ = assetManager;
    look_.setAssetManager(assetManager);
    capture_.single().setFilmAssetManager(assetManager);
    capture_.multiframe().setFilmAssetManager(assetManager);
}

void SessionEngine::setColorRenderProfile(uint32_t profileId, std::string importedProfileId) {
    rawrcam::tonemap_integration::ColorRenderProfile profile{};
    if (!rawrcam::tonemap_integration::colorRenderProfileFromId(profileId, &profile)) {
        LOGW("Rejected invalid color render profile id=%u", profileId);
        return;
    }
    std::lock_guard<std::mutex> lock(mu_);
    if (videoSession_.ready()) return;
    if (realtime_.setColorRenderProfile(profile, std::move(importedProfileId))) {
        tonemap::TonemapParams probe{};
        rawrcam::tonemap_integration::applyRenderProfile(profile, probe);
        sceneReferredRender_.store(probe.renderTransform == tonemap::RenderTransform::Log, std::memory_order_relaxed);
        videoSession_.renderLutChanged();
        // Also refreshes processing cached between recordings.
        {
            try {
                const auto renderLut = look_.videoRenderLut();
                videoSession_.updateRenderLut(renderLut ? &*renderLut : nullptr);
            } catch (const std::exception& error) {
                appendDiagnostic(std::string("VIDEO_RENDER_LUT_UPDATE_FAIL error=") + error.what());
                videoSession_.stop();
            }
        }
        appendDiagnostic(std::string("COLOR_RENDER_PROFILE selected=") +
                         rawrcam::tonemap_integration::colorRenderProfileName(profile));
    }
}

void SessionEngine::setMonitoringOverlay(uint32_t mode, float focusSensitivity) {
    std::lock_guard<std::mutex> lock(mu_);
    monitoringCoordinator_.setOverlay(mode, focusSensitivity);
}
void SessionEngine::setScopePresentationState(const rawrcam::monitoring::ScopePresentationState& state) {
    std::lock_guard<std::mutex> lock(mu_);
    monitoringCoordinator_.setScopePresentationState(state);
    swapchainRenderer_.setScopePresentationState(state);
}

void SessionEngine::setFocusLoupe(const rawrcam::presentation::LoupeDraw& loupe, bool meter) {
    std::lock_guard<std::mutex> lock(mu_);
    swapchainRenderer_.setLoupe(loupe);
    monitoringCoordinator_.scopes().setFocusMeter(loupe.enabled && meter, loupe.wx0, loupe.wy0, loupe.wx1, loupe.wy1);
}
float SessionEngine::focusMeterValue() { return monitoringCoordinator_.scopes().focusValue(); }
void SessionEngine::setViewfinderZoom(float crop) {
    std::lock_guard<std::mutex> lock(mu_);
    viewfinderZoom_.store(crop, std::memory_order_relaxed);
    // Mid lens switch: the new crop waits for the new camera's frames (the old lens's last frames, still being
    // developed, flashed at the new crop: 3.2x framing going up past 5x, ~7.5x coming back).
    if (zoomSwitchFromGeneration_ != 0 && cameraSetupGeneration_ <= zoomSwitchFromGeneration_) {
        realtime_.coordinator().setViewfinderZoomAfter(crop, zoomSwitchFromGeneration_);
    } else {
        zoomSwitchFromGeneration_ = 0;
        realtime_.coordinator().setViewfinderZoom(crop);
    }
    monitoringCoordinator_.scopes().setFocusZoom(crop);
    swapchainRenderer_.resetLogging();
}

void SessionEngine::armZoomForLensSwitch() {
    std::lock_guard<std::mutex> lock(mu_);
    zoomSwitchFromGeneration_ = std::max<uint64_t>(cameraSetupGeneration_, 1);
}

void SessionEngine::disarmZoomForLensSwitch() {
    std::lock_guard<std::mutex> lock(mu_);
    zoomSwitchFromGeneration_ = 0;
}

void SessionEngine::setColorMode(const std::string& mode) {
    std::lock_guard<std::mutex> lock(mu_);
    colorMode_ = mode.empty() ? "auto" : mode;
    realtime_.coordinator().setColorMode(colorMode_);
    appendDiagnostic("COLOR_MODE mode=" + colorMode_ + " owner=native_color_module");
}

void SessionEngine::setFilmSimEnabled(bool enabled) { look_.setFilmSimEnabled(enabled); }

void SessionEngine::setViewfinderDivisor(uint32_t divisor) { look_.setViewfinderDivisor(divisor); }

void SessionEngine::setFilmSimLook(const spektrafilm_native::FilmLook& look) { look_.setFilmSimLook(look); }

void SessionEngine::setQuickToneNativeValue(int target, float value) { look_.setQuickToneNativeValue(target, value); }

void SessionEngine::setTonemapParameters(float exposureEV, float blackPointEV, float shadowLiftEV, float midtoneLiftEV,
                                         float contrast, float whitePointEV, float highlightBiasEV, float saturation,
                                         float vibrance) {
    look_.setTonemapParameters(exposureEV, blackPointEV, shadowLiftEV, midtoneLiftEV, contrast, whitePointEV,
                               highlightBiasEV, saturation, vibrance);
}

void SessionEngine::setAgxParameters(const float* values) { look_.setAgxParameters(values); }
void SessionEngine::setLutStrength(float strength) { look_.setLutStrength(strength); }

}  // namespace rawrcam::session
