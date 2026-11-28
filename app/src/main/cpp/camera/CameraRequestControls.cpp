#include "camera/CameraRequestControls.h"

#include <camera/NdkCameraMetadataTags.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

#include <sys/system_properties.h>

#include "camera/Camera2PriorityCompat.h"
#include "camera/CameraAePriority.h"
#include "camera/CameraWorkarounds.h"
#include "camera/CameraRequestCadence.h"
#include "camera/ShotHandover.h"
#include "camera/StillFocus.h"
#include "camera/ViewfinderExposure.h"
#include "camera/WhiteBalanceMath.h"

namespace rawrcam::camera {
namespace {

float clampGain(float value) { return std::clamp(value, 1.0f, 4.0f); }

bool awbModeAdvertised(const CameraControlState& state, uint8_t awb) {
    const auto& modes = state.capabilities.supportedAwbModes;
    return std::find(modes.begin(), modes.end(), awb) != modes.end();
}

}  // namespace

void applyPreviewRequestDefaults(CameraRequest* request, const metadata::CameraContextMetadata& context,
                                 bool minimal) {
    if (!request) return;
    const uint8_t shadingMap = context.lensShadingMapWidth >= 2 && context.lensShadingMapHeight >= 2
                                   ? ACAMERA_STATISTICS_LENS_SHADING_MAP_MODE_ON
                                   : ACAMERA_STATISTICS_LENS_SHADING_MAP_MODE_OFF;
    const uint8_t distortion = ACAMERA_DISTORTION_CORRECTION_MODE_OFF;
    // X14U: the HAL reports no hot pixel map and its own correction doesn't reach RAW (camera 4 HIGH_QUALITY test,
    // 15 Nov: specks unchanged); develop finds them itself (detectHotPixelsInPackedRaw16).
    const uint8_t hotPixel = ACAMERA_HOT_PIXEL_MODE_OFF;
    const uint8_t hotPixelMap = ACAMERA_STATISTICS_HOT_PIXEL_MAP_MODE_ON;
    // Keep metadata enabled for every RAW frame without request swaps during bursts.
    (void)request->setU8(ACAMERA_STATISTICS_LENS_SHADING_MAP_MODE, 1, &shadingMap);
    if (minimal) return;
    (void)request->setU8(ACAMERA_DISTORTION_CORRECTION_MODE, 1, &distortion);
    (void)request->setU8(ACAMERA_HOT_PIXEL_MODE, 1, &hotPixel);
    (void)request->setU8(ACAMERA_STATISTICS_HOT_PIXEL_MAP_MODE, 1, &hotPixelMap);
}

CameraSessionCadenceApplyResult applyCameraSessionCadence(CameraRequest* request, const CameraControlState& state) {
    CameraSessionCadenceApplyResult result;
    if (!request) {
        result.status = kRequestInvalidParameter;
        return result;
    }
    const bool priority = state.exposureMode == ExposureControlMode::ShutterPriority ||
                          state.exposureMode == ExposureControlMode::IsoPriority;
    result.fpsRange = cameraTargetFpsRange(state, priority);
    if (result.fpsRange) {
        result.status =
            request->setI32(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, result.fpsRange->data());
    }
    return result;
}

CameraControlApplyResult applyCameraControlState(CameraRequest* request, const CameraControlState& state,
                                                 const CameraMeteringRequest& metering) {
    CameraControlApplyResult result{};
    if (!request) return result;

    const uint8_t controlMode = ACAMERA_CONTROL_MODE_AUTO;
    (void)request->setU8(ACAMERA_CONTROL_MODE, 1, &controlMode);

    const bool rawrAe = state.exposureMode != ExposureControlMode::Manual && state.rawrAeActive &&
                        state.rawrAeExposureTimeNs > 0 && state.rawrAeSensitivity > 0;
    // rawr AE is AE OFF with the app-solved pair: the same sensor ownership as M.
    const bool manual = state.exposureMode == ExposureControlMode::Manual || rawrAe;
    const uint8_t ae = manual ? ACAMERA_CONTROL_AE_MODE_OFF : ACAMERA_CONTROL_AE_MODE_ON;
    (void)request->setU8(ACAMERA_CONTROL_AE_MODE, 1, &ae);

    // AE priority is a distinct Camera2 mode (API 36). Explicitly write OFF for
    // ordinary Auto/Manual requests because this ACaptureRequest object is reused.
    uint8_t aePriority = camera2_priority::kOff;
    // rawr AE owns S/I itself (AE OFF); otherwise only the HAL's own modes.
    // S/I with neither owner (rawr ineligible, e.g. video) runs as Auto.
    if (!rawrAe && state.exposureMode == ExposureControlMode::ShutterPriority &&
        state.capabilities.halShutterPrioritySupported) {
        aePriority = camera2_priority::kSensorExposureTimePriority;
    } else if (!rawrAe && state.exposureMode == ExposureControlMode::IsoPriority &&
               state.capabilities.halIsoPrioritySupported) {
        aePriority = camera2_priority::kSensorSensitivityPriority;
    }
    result.halPriorityMode = aePriority != camera2_priority::kOff;
    if (state.capabilities.aePriorityTagAvailable) {
        if (state.capabilities.minimalRequestDefaults && aePriority == camera2_priority::kOff) {
            // Not asking for HAL priority AE: leave the tag to the template (and clear one set earlier).
            (void)request->setU8(camera2_priority::kAePriorityModeTag, 0, nullptr);
        } else {
            result.aePriorityRequested = true;
            result.aePriorityStatus =
                request->setU8(camera2_priority::kAePriorityModeTag, 1, &aePriority);
        }
    }

    // Priority AE still obeys CONTROL_AE_TARGET_FPS_RANGE. TEMPLATE_PREVIEW can
    // default to a 30/30 range, which prevents exposure-time priority from ever
    // going longer than ~33 ms. Give S/I the broadest advertised <=30 fps range.
    // Auto gets the user-configured floor (default 15) so the HAL can prefer
    // longer exposure over post-raw digital boost in low light; 30 == off and
    // restores template behavior. Manual removes the entry.
    if (const auto fps = cameraTargetFpsRange(state, aePriority != camera2_priority::kOff)) {
        result.aeTargetFpsRequested = true;
        result.aeTargetFpsStatus =
            request->setI32(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, fps->data());
    } else {
        (void)request->setI32(ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 0, nullptr);
    }

    // rawr AE in video mode holds the video preview cadence, as recording does.
    const int64_t framePeriodNs = state.recordingFps > 0 ? 1'000'000'000LL / state.recordingFps
                                  : rawrAe && state.videoMode && state.videoPreviewFps > 0
                                      ? 1'000'000'000LL / state.videoPreviewFps
                                      : 0;
    const int64_t exposureMaximum = framePeriodNs > 0
        ? std::min(state.capabilities.exposureTimeMaxNs, framePeriodNs)
        : state.capabilities.exposureTimeMaxNs;
    // M: shutters past the viewfinder cap run only on capture (ViewfinderExposure.h).
    const auto manualPair = viewfinderManualPair(state);
    const bool manualMode = state.exposureMode == ExposureControlMode::Manual;
    const int64_t exposure = std::clamp(rawrAe       ? state.rawrAeExposureTimeNs
                                        : manualMode ? manualPair.exposureTimeNs
                                                     : state.requestedExposureTimeNs,
                                        state.capabilities.exposureTimeMinNs,
                                        std::max(state.capabilities.exposureTimeMinNs, exposureMaximum));
    const int32_t sensitivity = std::clamp(rawrAe       ? state.rawrAeSensitivity
                                           : manualMode ? manualPair.sensitivity
                                                        : state.requestedSensitivity,
                                           state.capabilities.sensitivityMin, state.capabilities.sensitivityMax);

    // The repeating ACaptureRequest is reused. Normalize SENSOR_* ownership on
    // every mode so stale values from M/S/I cannot leak into a different AE mode.
    // Camera2 NDK removes an entry when count=0 and data=nullptr.
    if (manual) {
        (void)request->setI64(ACAMERA_SENSOR_EXPOSURE_TIME, 1, &exposure);
        (void)request->setI32(ACAMERA_SENSOR_SENSITIVITY, 1, &sensitivity);

        if (state.capabilities.maxFrameDurationNs > 0) {
            constexpr int64_t kThirtyFpsFrameNs = 33333333LL;
            const int64_t frameDuration = framePeriodNs > 0 ? framePeriodNs :
                std::min(state.capabilities.maxFrameDurationNs, std::max(kThirtyFpsFrameNs, exposure));
            (void)request->setI64(ACAMERA_SENSOR_FRAME_DURATION, 1, &frameDuration);
        }
    } else if (aePriority == camera2_priority::kSensorExposureTimePriority) {
        // S: app owns shutter; AE owns ISO + frame duration.
        (void)request->setI64(ACAMERA_SENSOR_EXPOSURE_TIME, 1, &exposure);
        (void)request->setI32(ACAMERA_SENSOR_SENSITIVITY, 0, nullptr);
        (void)request->setI64(ACAMERA_SENSOR_FRAME_DURATION, 0, nullptr);
    } else if (aePriority == camera2_priority::kSensorSensitivityPriority) {
        // I: app owns ISO; AE owns shutter + frame duration.
        (void)request->setI32(ACAMERA_SENSOR_SENSITIVITY, 1, &sensitivity);
        (void)request->setI64(ACAMERA_SENSOR_EXPOSURE_TIME, 0, nullptr);
        (void)request->setI64(ACAMERA_SENSOR_FRAME_DURATION, 0, nullptr);
    } else {
        // A: return all sensor exposure ownership to Camera2 AE.
        (void)request->setI64(ACAMERA_SENSOR_EXPOSURE_TIME, 0, nullptr);
        (void)request->setI32(ACAMERA_SENSOR_SENSITIVITY, 0, nullptr);
        (void)request->setI64(ACAMERA_SENSOR_FRAME_DURATION, 0, nullptr);
    }

    // X14U 50 MP shot: the shot's session runs on the 12 MP view's measured exposure (AE OFF) instead of starting
    // its own AE cold; M keeps the user's own pair.
    const auto pin = shotHandover().carriedExposure();
    const bool pinExposure = pin && state.exposureMode != ExposureControlMode::Manual &&
                             state.capabilities.manualExposureSupported;
    if (pinExposure) {
        const uint8_t aeOff = ACAMERA_CONTROL_AE_MODE_OFF;
        (void)request->setU8(ACAMERA_CONTROL_AE_MODE, 1, &aeOff);
        if (state.capabilities.aePriorityTagAvailable) {
            const uint8_t off = camera2_priority::kOff;
            (void)request->setU8(camera2_priority::kAePriorityModeTag, 1, &off);
        }
        // The 14 Ultra's 0.5x full-size readout is less sensitive: more ISO (past the top of the range, the rest as a
        // longer shutter), so the 50 MP RAW matches the 12 MP view's brightness.
        const float gain = ShotHandover::fullSizeGain(state.capabilities.cameraId);
        const int32_t isoMax = std::max(state.capabilities.sensitivityMin, state.capabilities.sensitivityMax);
        const double wantedIso = double(pin->sensitivity) * gain;
        const double extraShutter = wantedIso > isoMax && isoMax > 0 ? wantedIso / isoMax : 1.0;
        const int64_t pinnedExposure =
            std::clamp(int64_t(std::llround(double(pin->exposureTimeNs) * extraShutter)),
                       state.capabilities.exposureTimeMinNs,
                       std::max(state.capabilities.exposureTimeMinNs, state.capabilities.exposureTimeMaxNs));
        const int32_t pinnedSensitivity = std::clamp(int32_t(std::lround(std::min(wantedIso, double(isoMax)))),
                                                     state.capabilities.sensitivityMin, isoMax);
        (void)request->setI64(ACAMERA_SENSOR_EXPOSURE_TIME, 1, &pinnedExposure);
        (void)request->setI32(ACAMERA_SENSOR_SENSITIVITY, 1, &pinnedSensitivity);
        if (state.capabilities.maxFrameDurationNs > 0) {
            constexpr int64_t kThirtyFpsFrameNs = 33333333LL;
            const int64_t frameDuration =
                std::min(state.capabilities.maxFrameDurationNs, std::max(kThirtyFpsFrameNs, pinnedExposure));
            (void)request->setI64(ACAMERA_SENSOR_FRAME_DURATION, 1, &frameDuration);
        }
    }

    // AE antibanding. The repeating request is reused so normalize every
    // time; unsupported values fall back to AUTO (or the first advertised).
    // No effect in Manual (AE OFF) per Camera2 docs, but harmless to write.
    {
        uint8_t banding = state.requestedAntibandingMode;
        const auto& supported = state.capabilities.supportedAntibandingModes;
        if (!supported.empty() &&
            std::find(supported.begin(), supported.end(), banding) == supported.end()) {
            const auto autoIt = std::find(supported.begin(), supported.end(),
                                          static_cast<uint8_t>(ACAMERA_CONTROL_AE_ANTIBANDING_MODE_AUTO));
            banding = autoIt != supported.end() ? *autoIt : supported.front();
        }
        (void)request->setU8(ACAMERA_CONTROL_AE_ANTIBANDING_MODE, 1, &banding);
    }

    if (!manual && !pinExposure) {
        const uint8_t aeLock = ACAMERA_CONTROL_AE_LOCK_OFF;
        (void)request->setU8(ACAMERA_CONTROL_AE_LOCK, 1, &aeLock);

        const int32_t ev = std::clamp(state.requestedEvSteps,
                                      state.capabilities.evMinSteps, state.capabilities.evMaxSteps);
        (void)request->setI32(ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, 1, &ev);
    }

    // White balance. The repeating ACaptureRequest is reused, so every branch
    // normalizes full ownership: presets write AWB + HIGH_QUALITY and remove
    // stale manual GAINS/TRANSFORM; manual writes AWB OFF + TRANSFORM_MATRIX +
    // relative GAINS + the frozen HAL transform. Removal uses count=0/data=null.
    //
    // Manual gains are relative to the entry baseline captured on
    // AUTO/preset->MANUAL (Camera2-endorsed: TRANSFORM_MATRIX with the
    // HAL-provided gains+transform reproduces the previous white point).
    // Rendering = entry x forward(requested)/forward(entry), so entering
    // manual is seamless and returning sliders to the entry values restores
    // the preset/auto look exactly. Absolute Helland gains are the fallback
    // when no entry baseline exists (e.g. entered before any HAL result).
    result.wbManualRequested = state.whiteBalanceMode == WhiteBalanceControlMode::ManualTempTint;
    // X14U 50 MP shot: the shot's session uses the 12 MP view's white balance (gains and transform as applied there)
    // instead of starting its own auto white balance cold.
    const auto carried = shotHandover().carried();
    // In the 3x 50 MP companion session the carry stands in for auto white balance only.
    if (carried && state.capabilities.manualGainsSupported &&
        !(result.wbManualRequested && shotHandover().companionCarry())) {
        const uint8_t awb = ACAMERA_CONTROL_AWB_MODE_OFF;
        const uint8_t color = ACAMERA_COLOR_CORRECTION_MODE_TRANSFORM_MATRIX;
        result.wbStatus = request->setU8(ACAMERA_CONTROL_AWB_MODE, 1, &awb);
        (void)request->setU8(ACAMERA_COLOR_CORRECTION_MODE, 1, &color);
        std::array<float, 4> gains = carried->gains;
        for (auto& g : gains) g = clampGain(g);
        (void)request->setFloat(ACAMERA_COLOR_CORRECTION_GAINS, 4, gains.data());
        RequestRational transform[9];
        for (size_t i = 0; i < 9; ++i) {
            transform[i].numerator = static_cast<int32_t>(std::lround(carried->transform[i] * 10000.0f));
            transform[i].denominator = 10000;
        }
        (void)request->setRational(ACAMERA_COLOR_CORRECTION_TRANSFORM, 9, transform);
    } else if (result.wbManualRequested && state.capabilities.manualGainsSupported &&
        state.hasManualWhiteBalanceTransformSeed) {
        const uint8_t awb = ACAMERA_CONTROL_AWB_MODE_OFF;
        const uint8_t color = ACAMERA_COLOR_CORRECTION_MODE_TRANSFORM_MATRIX;
        result.wbStatus = request->setU8(ACAMERA_CONTROL_AWB_MODE, 1, &awb);
        (void)request->setU8(ACAMERA_COLOR_CORRECTION_MODE, 1, &color);
        const std::array<float, 4> fwdNew =
            whiteBalanceGainsForTempTint(state.requestedWhiteBalanceTemperatureK, state.requestedWhiteBalanceTint);
        std::array<float, 4> gains = fwdNew;
        if (state.hasManualWhiteBalanceEntryGains) {
            const std::array<float, 4> fwdEntry = whiteBalanceGainsForTempTint(
                state.manualWhiteBalanceEntryTempK, state.manualWhiteBalanceEntryTint);
            const float entryR = state.manualWhiteBalanceEntryGains[0];
            const float entryG = 0.5f * (state.manualWhiteBalanceEntryGains[1] +
                                         state.manualWhiteBalanceEntryGains[2]);
            const float entryB = state.manualWhiteBalanceEntryGains[3];
            if (fwdEntry[0] > 1.0e-6f && fwdEntry[1] > 1.0e-6f && fwdEntry[3] > 1.0e-6f &&
                std::isfinite(entryR) && std::isfinite(entryG) && std::isfinite(entryB)) {
                gains = {clampGain(entryR * fwdNew[0] / fwdEntry[0]),
                         clampGain(entryG * fwdNew[1] / fwdEntry[1]),
                         clampGain(entryG * fwdNew[1] / fwdEntry[1]),
                         clampGain(entryB * fwdNew[3] / fwdEntry[3])};
            }
        }
        (void)request->setFloat(ACAMERA_COLOR_CORRECTION_GAINS, 4, gains.data());
        // COLOR_CORRECTION_TRANSFORM is rational[9]; carry the HAL seed at
        // 1e-4 precision (magnitude stays within [-1.5, 3.0], fitting int32).
        RequestRational transform[9];
        for (size_t i = 0; i < 9; ++i) {
            transform[i].numerator = static_cast<int32_t>(std::lround(state.manualWhiteBalanceTransformSeed[i] * 10000.0f));
            transform[i].denominator = 10000;
        }
        (void)request->setRational(ACAMERA_COLOR_CORRECTION_TRANSFORM, 9, transform);
    } else {
        if (result.wbManualRequested) result.wbManualFallbackToAuto = true;
        uint8_t awb = static_cast<uint8_t>(state.whiteBalanceMode);
        if (state.whiteBalanceMode == WhiteBalanceControlMode::ManualTempTint || !awbModeAdvertised(state, awb)) {
            awb = ACAMERA_CONTROL_AWB_MODE_AUTO;
        }
        const uint8_t color = ACAMERA_COLOR_CORRECTION_MODE_HIGH_QUALITY;
        result.wbStatus = request->setU8(ACAMERA_CONTROL_AWB_MODE, 1, &awb);
        (void)request->setU8(ACAMERA_COLOR_CORRECTION_MODE, 1, &color);
        (void)request->setFloat(ACAMERA_COLOR_CORRECTION_GAINS, 0, nullptr);
        (void)request->setRational(ACAMERA_COLOR_CORRECTION_TRANSFORM, 0, nullptr);
    }

    uint8_t af = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    const bool focusHold = state.viewfinderFocusHold && std::isfinite(*state.viewfinderFocusHold) &&
                           state.focusMode == FocusControlMode::Continuous && !state.tapAfActive &&
                           state.capabilities.manualFocusSupported;
    if (state.focusMode == FocusControlMode::Manual || focusHold) {
        af = ACAMERA_CONTROL_AF_MODE_OFF;
    } else if (state.focusMode == FocusControlMode::LockedAuto || state.tapAfActive) {
        af = ACAMERA_CONTROL_AF_MODE_AUTO;
    }
    result.focusStatus = request->setU8(ACAMERA_CONTROL_AF_MODE, 1, &af);

    // Face detection feeds the face boxes + face-steered continuous AF. FULL
    // carries per-face scores; fall back to SIMPLE. The repeating request is
    // reused, so explicitly write OFF when unsupported.
    const uint8_t faceDetect = !state.capabilities.faceDetectSupported
                                   ? ACAMERA_STATISTICS_FACE_DETECT_MODE_OFF
                                   : (state.capabilities.faceDetectFullMode
                                          ? ACAMERA_STATISTICS_FACE_DETECT_MODE_FULL
                                          : ACAMERA_STATISTICS_FACE_DETECT_MODE_SIMPLE);
    if (state.capabilities.minimalRequestDefaults)
        (void)request->setU8(ACAMERA_STATISTICS_FACE_DETECT_MODE, 0, nullptr);
    else
        (void)request->setU8(ACAMERA_STATISTICS_FACE_DETECT_MODE, 1, &faceDetect);

    const uint8_t ois = (state.capabilities.oisSupported && state.oisEnabled)
                            ? ACAMERA_LENS_OPTICAL_STABILIZATION_MODE_ON
                            : ACAMERA_LENS_OPTICAL_STABILIZATION_MODE_OFF;
    (void)request->setU8(ACAMERA_LENS_OPTICAL_STABILIZATION_MODE, 1, &ois);

    if (focusHold) {
        const float diopters = *state.viewfinderFocusHold;
        const auto status = request->setFloat(ACAMERA_LENS_FOCUS_DISTANCE, 1, &diopters);
        if (status != kRequestOk) result.focusStatus = status;
    } else if ((state.focusMode == FocusControlMode::Manual) && state.capabilities.manualFocusSupported) {
        const float diopters = requestedFocusDiopters(state);
        const auto status = request->setFloat(ACAMERA_LENS_FOCUS_DISTANCE, 1, &diopters);
        if (status != kRequestOk) result.focusStatus = status;
    }
    // keepTemplateRegions: with no tap region the request keeps the template's own, never an emptied one.
    if (writesRegionEntry(metering.afRegion.has_value(), state.capabilities.keepTemplateRegions))
        (void)request->setI32(ACAMERA_CONTROL_AF_REGIONS, metering.afRegion ? 5 : 0,
                                           metering.afRegion ? metering.afRegion->data() : nullptr);
    if (writesRegionEntry(metering.aeRegion.has_value(), state.capabilities.keepTemplateRegions))
        (void)request->setI32(ACAMERA_CONTROL_AE_REGIONS, metering.aeRegion ? 5 : 0,
                                           metering.aeRegion ? metering.aeRegion->data() : nullptr);
    const uint8_t trigger =
        metering.trigger == CameraFocusTrigger::Start ? ACAMERA_CONTROL_AF_TRIGGER_START
        : metering.trigger == CameraFocusTrigger::Cancel && !state.capabilities.tapAfHoldsUntilNextTap
            ? ACAMERA_CONTROL_AF_TRIGGER_CANCEL
            : ACAMERA_CONTROL_AF_TRIGGER_IDLE;
    (void)request->setU8(ACAMERA_CONTROL_AF_TRIGGER, 1, &trigger);
    // X14U 50 MP shot: the lens goes to where the 12 MP view had focused (AF: AF OFF + its measured distance; MF: the
    // user's distance).
    const bool manualFocus = state.focusMode == FocusControlMode::Manual;
    // Test (Nov 26): debug.rawr.shot50_focus 0 sends no distance: AF AUTO with no trigger, so the HAL has no reason to
    // move the lens and it may stay where the 12 MP view focused it (the full-size mode maps diopters to the motor
    // differently, so a sent distance lands elsewhere). 1 (default): AF OFF + distance as below.
    char focusProp[PROP_VALUE_MAX] = {};
    const bool sendDistance =
        !(__system_property_get("debug.rawr.shot50_focus", focusProp) > 0 && focusProp[0] == '0');
    if (pin && !sendDistance && state.capabilities.manualFocusSupported) {
        const uint8_t afAuto = ACAMERA_CONTROL_AF_MODE_AUTO;
        const uint8_t idle = ACAMERA_CONTROL_AF_TRIGGER_IDLE;
        result.focusStatus = request->setU8(ACAMERA_CONTROL_AF_MODE, 1, &afAuto);
        (void)request->setU8(ACAMERA_CONTROL_AF_TRIGGER, 1, &idle);
    } else if (pin && shotHandover().companionShot() && !manualFocus) {
        // "Rawr view" 50 MP (companion session): the shot job runs AF there (tap region + trigger above); a carried
        // distance would land elsewhere in the full-size mode.
    } else if (pin && (manualFocus || pin->focusDiopters) && state.capabilities.manualFocusSupported) {
        const uint8_t afOff = ACAMERA_CONTROL_AF_MODE_OFF;
        const uint8_t idle = ACAMERA_CONTROL_AF_TRIGGER_IDLE;
        const float diopters = manualFocus ? requestedFocusDiopters(state) : *pin->focusDiopters;
        result.focusStatus = request->setU8(ACAMERA_CONTROL_AF_MODE, 1, &afOff);
        (void)request->setFloat(ACAMERA_LENS_FOCUS_DISTANCE, 1, &diopters);
        (void)request->setU8(ACAMERA_CONTROL_AF_TRIGGER, 1, &idle);
    }
    return result;
}

}  // namespace rawrcam::camera
