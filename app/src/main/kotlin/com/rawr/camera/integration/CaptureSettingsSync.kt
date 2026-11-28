package com.rawr.camera.integration

import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SetControlSurfaceStyle
import com.rawr.camera.architecture.SyncToneControls
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.settings.model.*
import com.rawr.camera.video.toVideoImageSettings
import com.rawr.camera.video.VideoProfileFeature
import com.rawr.camera.video.videoProfileAllows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/** Projects settings onto native preview and reports initial submission to its start gate. */
internal class CaptureSettingsSync(
    private val scope: CoroutineScope,
    private val values: Flow<SettingsValues>,
    private val latestSettings: () -> SettingsValues,
    private val previewCoordinator: RawPreviewCoordinator,
    private val controller: CaptureScreenController
) {
    private val bindings = mutableListOf<suspend () -> Unit>()
    private var firstApplies = 0
    private var started = false

    fun start() {
        check(!started)
        started = true
        // Diagnostics always record (bounded in native), so a bundle exported
        // after a problem already holds the evidence: no switch to remember.
        previewCoordinator.setPersistentDiagnosticsEnabled(true)
        previewCoordinator.setInternalTraceCaptureEnabled(true)
        syncSetting({ it.controlSurfaceStyle }) { controller.dispatch(SetControlSurfaceStyle(it)) }
        syncSetting({ it.oisEnabledPreference }) { previewCoordinator.setOisEnabled(it) }
        // Per-lens overrides change settings, not the lens the camera runs (no restart when one changes).
        syncSetting({ it.lensProfiles?.map { lens -> lens.copy(overrides = emptyMap()) } }) { previewCoordinator.setLensProfiles(it) }
        // Start on the last used lens. Startup only: later values come from
        // the user's own switches, and replaying one could undo a newer switch.
        // Native falls back to the first lens if this one left the profile.
        syncStream(values.map { it.lastLensId }.take(1)) { id -> id?.let(previewCoordinator::selectLens) }
        syncSetting({ it.antiFlicker }) {
            previewCoordinator.setAntibandingMode(com.rawr.camera.settings.model.antibandingModeFor(it))
        }
        syncSetting({ it.highlightProtection }) {
            previewCoordinator.setPostGainKneeWidthEv(com.rawr.camera.settings.model.postGainKneeWidthEvFor(it))
        }
        syncSetting({ if (it.isVideo) it.videoAutoKeyExposure else it.autoKeyExposure }) {
            previewCoordinator.setAutoKeyMode(it.nativeId)
        }
        // Photo local tone mapping; video always takes the adaptive curve.
        syncSetting({
            (if (!it.isVideo && it.photoToneMapping == PhotoToneMapping.Local) it.localToneShadowsEv else 0f) to it.localToneTuning
        }) { (shadows, tuning) -> previewCoordinator.setLocalTone(shadows, tuning) }
        // The next photo's merged frame count: the viewfinder's noise limit
        // on the local tone lift matches the still's.
        syncSetting({
            if (!it.isVideo && it.experimentalMultiframeEnabled)
                it.multiframeTuning.maxFrames.coerceIn(1, 30)
            else 1
        }) { previewCoordinator.setExpectedMergeFrames(it) }
        syncSetting({ it.maxPostGainId }) {
            previewCoordinator.setMaxAePostGain(com.rawr.camera.settings.model.maxPostGainForId(it))
        }
        syncSetting({ it.aePriorityDisabled }) { previewCoordinator.setAePriorityDisabled(it) }
        syncSetting({ it.rawrAe }) { previewCoordinator.setRawrAutoExposure(it) }
        syncSetting({ it.autoMinFpsId }) {
            previewCoordinator.setAutoMinFps(com.rawr.camera.settings.model.autoMinFpsForId(it))
        }
        syncSetting({ it.persistZslRingEnabled }) { previewCoordinator.setPersistZslRingEnabled(it) }
        syncSetting({ it.photoLensShadingEnabled }) { previewCoordinator.setLensShadingCorrectionEnabled(it) }
        // The viewfinder frames what the current mode saves.
        syncSetting({
            if (it.isVideo) it.videoDistortionCorrectionEnabled && it.videoProfileAllows(VideoProfileFeature.LensCorrection)
            else it.distortionCorrectionEnabled
        }) {
            previewCoordinator.setDistortionCorrectionEnabled(it)
        }
        // Idle video uses RawPreview until recording supplies the monitor
        // image. Match its recovery switch to the recorder, including when
        // entering/leaving Video; photo recovery can alter LOG highlights.
        syncSetting({
            if (it.isVideo) it.videoHighlightEnabled && it.videoProfileAllows(VideoProfileFeature.Highlight) else it.photoHighlightEnabled
        }) {
            previewCoordinator.setHighlightReconstructionEnabled(it)
        }
        syncSetting({ it.videoProfileAllows(VideoProfileFeature.Dcp) }) { previewCoordinator.setVideoProfileDcp(it) }
        syncSetting({ it.toVideoImageSettings() }) {
            previewCoordinator.setVideoImageSettings(it)
            // FCC and defringe are part of the prewarmed pipeline.
            prewarmVideo()
        }
        syncSetting({ it.effectiveVideoBitDepth() }) { prewarmVideo() }
        syncSetting({ it.experimentalZeroCopyEnabled }) { previewCoordinator.setExperimentalZeroCopy(it) }
        // X14U (Nov 20): `adb shell setprop debug.rawr.video_mf_off 1` stops the HDR+ frame ring in Video (it copies
        // every RAW frame for a burst video never takes); read on each settings change, so switch modes after setting it.
        syncSetting({ it.experimentalMultiframeEnabled && !(it.isVideo && DebugProps.int("debug.rawr.video_mf_off") == 1) }) {
            previewCoordinator.setExperimentalMultiframeEnabled(it)
        }
        syncSetting({ it.persistentEngineEnabled }) { previewCoordinator.setPersistentEngineEnabled(it) }
        // Keyed on profile identity + LUT structure (tone normalized out):
        // native profile switches rebuild the tonemap engine, so tone-only
        // edits must not retrigger them (tone flows via setTonemapParameters).
        syncSetting({ it.renderProfileSyncKey() }) { (profile, id, _) ->
            previewCoordinator.setColorRenderProfile(profile, id)
        }
        // Film simulation is a photo look; the Video viewfinder must not apply it.
        syncSetting({ it.filmSimEnabled && !it.isVideo }) { previewCoordinator.setFilmSim(it) }
        syncSetting({ it.viewfinderDivisor }) { previewCoordinator.setViewfinderDivisor(it) }
        syncSetting({ it.filmSimLook }) { look ->
            previewCoordinator.setFilmSimLook(look.toFloatArray(), look.toIntArray())
        }
        syncSetting({ it.peakingSensitivityId }) { id ->
            val sensitivity =
                when (id) {
                    "peaking.low" -> 0.25f
                    "peaking.high" -> 0.75f
                    else -> 0.50f
                }
            previewCoordinator.setFocusPeakingSensitivity(sensitivity)
        }
        syncStream(LiveTonemapState.values.filterNotNull()) { applyTonemapState(it) }
        syncStream(LiveTonemapState.agx.filterNotNull()) { previewCoordinator.setAgxParameters(it) }
        // X14U: the selected imported LUT's own strength (1 for every other profile), per frame: no engine rebuild.
        syncStream(
            kotlinx.coroutines.flow.combine(values.map { it.activeUserLutId() }.distinctUntilChanged(),
                LutStrengthSettings.strengths) { id, _ -> LutStrengthSettings.strengthFor(id) }
                .distinctUntilChanged()
        ) { previewCoordinator.setLutStrength(it) }
        firstApplies = bindings.size
        bindings.forEach { collect -> scope.launch { collect() } }
    }

    private fun <T> syncSetting(select: (SettingsValues) -> T, apply: suspend (T) -> Unit) {
        syncStream(values.map(select).distinctUntilChanged(), apply)
    }

    private fun <T> syncStream(source: Flow<T>, apply: suspend (T) -> Unit) {
        bindings += {
            var first = true
            source.collect { value ->
                apply(value)
                if (first) {
                    first = false
                    firstApplies--
                    if (firstApplies == 0) previewCoordinator.setInitialConfigReady()
                }
            }
        }
    }

    /**
     * Arms/clears the native idle-preview crop to the exact record window.
     * 16:9 outputs crop the open-gate preview to the centered record rect;
     * Open Gate and Photo show the full frame (clear). Output sizes are
     * sensor-landscape, matching the recorder's frame sizes.
     */
    fun updatePreviewCrop(mode: CaptureMode, crop: com.rawr.camera.video.VideoCrop) {
        // X14U video crop: the native side takes the sensor window, the viewfinder frames it.
        val (source, output) = crop.sizes(rawSize().first, rawSize().second)
        previewCoordinator.setVideoCropSource(source.width, source.height)
        when {
            mode != CaptureMode.Video -> previewCoordinator.clearVideoPreviewCrop()
            crop.isOpenGate && crop.factor == 1f -> previewCoordinator.clearVideoPreviewCrop()
            else -> previewCoordinator.setVideoPreviewCropOut(output.width, output.height)
        }
        prewarmVideo(mode, crop)
    }

    /** The active camera's RAW size (remembered for the crop panel), or the last one known. */
    private fun rawSize(): Pair<Int, Int> {
        runCatching {
            val camera = org.json.JSONObject(previewCoordinator.cameraControlSnapshot())
            val w = camera.optInt("rawWidth")
            val h = camera.optInt("rawHeight")
            if (w > 0 && h > 0) {
                com.rawr.camera.video.VideoCrop.lastRawWidth = w
                com.rawr.camera.video.VideoCrop.lastRawHeight = h
            }
        }
        return com.rawr.camera.video.VideoCrop.lastRawWidth to com.rawr.camera.video.VideoCrop.lastRawHeight
    }

    /** Builds (Video) or frees (Photo) the recording pipeline so recording starts at once. */
    fun prewarmVideo(
        mode: CaptureMode = controller.state.value.captureMode,
        crop: com.rawr.camera.video.VideoCrop = controller.state.value.videoCrop
    ) {
        if (mode != CaptureMode.Video) {
            previewCoordinator.releaseVideoProcessing()
            return
        }
        val output = crop.sizes(rawSize().first, rawSize().second).second
        val (width, height) = output.width to output.height
        previewCoordinator.prewarmVideo(width, height, latestSettings().effectiveVideoBitDepth())
    }

    private fun applyTonemapState(tone: com.rawr.camera.model.ImageToneState) {
        runCatching {
            previewCoordinator.setTonemapParameters(tone)
        }.onFailure {
            // A native hiccup must never cancel the LiveTonemapState collector,
            // or settings -> capture tone sync would die for the rest of the session.
        }
        controller.dispatch(SyncToneControls.fromImageTone(tone))
    }

}
