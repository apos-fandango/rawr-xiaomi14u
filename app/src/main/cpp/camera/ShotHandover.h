#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>

#include <sys/system_properties.h>

namespace rawrcam::camera {

// X14U 50 MP shot: the viewfinder runs on the lens's 12 MP setup and the shot on its run-as lens (a separate camera
// session). These carry the 12 MP view's white balance into the shot's session and hold the viewfinder on its last
// 12 MP frame meanwhile, so neither the photo nor the screen shows the new session's cold start.

struct AppliedWhiteBalance {
    std::array<float, 4> gains{1, 1, 1, 1};
    std::array<float, 9> transform{1, 0, 0, 0, 1, 0, 0, 0, 1};
};

// What the 12 MP view's frames were exposed and focused with (capture results).
struct AppliedExposure {
    int64_t exposureTimeNs = 0;
    int32_t sensitivity = 0;
    std::optional<float> focusDiopters;
};

class ShotHandover {
public:
    // Every frame: the white balance the camera applied (any mode).
    void noteApplied(const std::array<float, 4>& gains, const std::array<float, 9>& transform) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (companionCarry_) return;  // the 3x 50 MP session's stuck gains never replace the 12 MP view's
        last_ = AppliedWhiteBalance{gains, transform};
    }
    // Every viewfinder frame: the exposure and lens position the camera reported.
    void noteExposure(int64_t exposureTimeNs, int32_t sensitivity, std::optional<float> focusDiopters) {
        if (exposureTimeNs <= 0 || sensitivity <= 0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (carrying_.load(std::memory_order_relaxed)) return;  // the shot's own frames never replace the 12 MP view's
        lastExposure_ = AppliedExposure{exposureTimeNs, sensitivity,
                                        focusDiopters && std::isfinite(*focusDiopters) && *focusDiopters >= 0
                                            ? focusDiopters : std::nullopt};
    }
    // On: requests use the last applied white balance, exposure and lens position until off. The shot's session
    // otherwise starts auto exposure cold (darker, a different shutter: diagnostics 14 Nov, 3.2x) and its full-size
    // mode may not focus where the 12 MP view did.
    void setCarry(bool on) {
        std::lock_guard<std::mutex> lock(mutex_);
        carried_ = on ? last_ : std::nullopt;
        carriedExposure_ = on ? lastExposure_ : std::nullopt;
        carrying_.store(on, std::memory_order_relaxed);
    }
    // X14U 3x 50 MP (camera-processed viewfinder): the camera doesn't run auto white balance in that mode (its
    // gains stay where the session started: diagnostics Nov 27 15:48, the DNG's AsShotNeutral = the start gains, a
    // yellow cast under warm light). While such a session runs, requests carry the last 12 MP view's white balance
    // (auto only: a manual white balance still wins). Set at every session start.
    void setCompanionCarry(bool on) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (on && !companionCarry_ && !carrying_.load(std::memory_order_relaxed)) carried_ = last_;
        if (!on && companionCarry_ && !carrying_.load(std::memory_order_relaxed)) carried_ = std::nullopt;
        companionCarry_ = on;
    }
    bool companionCarry() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return companionCarry_;
    }
    // Gain the shot's camera needs at the carried exposure. Xiaomi 14 Ultra only: the 0.5x (camera 3) full-size
    // readout gives 0.70x the binned mode's raw signal at the same shutter and ISO (diagnostics 14 Nov: 47.5/68.2,
    // 64.1/91.4, 66.3/93.3). Other phones' camera ids mean other lenses: no gain.
    // The 14 Ultra's IMX858 (0.5x camera 3, 3x camera 4) full-size readout is ~0.5 EV less sensitive than its
    // 12 MP mode at the same settings; the 1x (camera 2) isn't.
    static float fullSizeGain(const std::string& cameraId) {
        return (cameraId == "3" || cameraId == "4") && isXiaomi14Ultra() ? 1.0f / 0.70f : 1.0f;
    }
    // X14U "Rawr view" 50 MP: the shot's session is a companion-viewfinder one (camera-processed preview + still
    // RAW, opMode 0x900F). Its frames aren't drawn (the 12 MP view holds), its focus comes from an AF run in that
    // session (a carried distance lands elsewhere in the full-size mode), and they are counted for the shot job.
    void setCompanionShot(bool on) {
        companionShot_.store(on, std::memory_order_relaxed);
        newCompanionFrames_.store(0, std::memory_order_relaxed);
    }
    bool companionShot() const { return companionShot_.load(std::memory_order_relaxed); }
    void noteCompanionFrame() {
        if (hold_.load(std::memory_order_relaxed)) newCompanionFrames_.fetch_add(1, std::memory_order_relaxed);
    }
    int64_t newCompanionFrames() const { return newCompanionFrames_.load(std::memory_order_relaxed); }
    std::optional<AppliedExposure> carriedExposure() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return carriedExposure_;
    }
    // A 50 MP shot's camera switches are under way: the new session keeps the exposure and focus intent even on a
    // different camera (the ultra-wide's 50 MP runs on camera 3, its viewfinder on camera 0).
    bool carrying() const { return carrying_.load(std::memory_order_relaxed); }
    std::optional<AppliedWhiteBalance> carried() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return carried_;
    }

    // Viewfinder hold: frames are developed but not shown; counts the frames of sessions started since the hold.
    void setHold(bool on) {
        holdStartGeneration_.store(lastGeneration_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        newFrames_.store(0, std::memory_order_relaxed);
        newFullSizeFrames_.store(0, std::memory_order_relaxed);
        newCompanionFrames_.store(0, std::memory_order_relaxed);
        hold_.store(on, std::memory_order_relaxed);
    }
    bool hold() const { return hold_.load(std::memory_order_relaxed); }
    // Every frame; true when it must not be shown.
    bool noteFrame(uint64_t generation, uint64_t rawPixels) {
        lastGeneration_.store(generation, std::memory_order_relaxed);
        if (!hold_.load(std::memory_order_relaxed)) return false;
        if (generation != holdStartGeneration_.load(std::memory_order_relaxed)) {
            newFrames_.fetch_add(1, std::memory_order_relaxed);
            // Full size: well above the 12.5 MP viewfinder modes.
            if (rawPixels > 30'000'000ull) newFullSizeFrames_.fetch_add(1, std::memory_order_relaxed);
        }
        return true;
    }
    int64_t newFrames() const { return newFrames_.load(std::memory_order_relaxed); }
    int64_t newFullSizeFrames() const { return newFullSizeFrames_.load(std::memory_order_relaxed); }

private:
    static bool isXiaomi14Ultra() {
        static const bool is = [] {
            char device[PROP_VALUE_MAX] = {};
            __system_property_get("ro.product.device", device);
            return std::string(device) == "aurora";
        }();
        return is;
    }
    mutable std::mutex mutex_;
    std::optional<AppliedWhiteBalance> last_;
    std::optional<AppliedWhiteBalance> carried_;
    std::optional<AppliedExposure> lastExposure_;
    std::optional<AppliedExposure> carriedExposure_;
    bool companionCarry_ = false;
    std::atomic<bool> hold_{false};
    std::atomic<bool> carrying_{false};
    std::atomic<uint64_t> lastGeneration_{0};
    std::atomic<uint64_t> holdStartGeneration_{0};
    std::atomic<int64_t> newFrames_{0};
    std::atomic<int64_t> newFullSizeFrames_{0};
    std::atomic<bool> companionShot_{false};
    std::atomic<int64_t> newCompanionFrames_{0};
};

inline ShotHandover& shotHandover() noexcept {
    static ShotHandover handover;
    return handover;
}

}  // namespace rawrcam::camera
