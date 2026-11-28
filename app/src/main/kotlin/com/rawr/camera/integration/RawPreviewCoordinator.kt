package com.rawr.camera.integration

import android.app.Application
import android.util.Log
import android.view.Surface
import com.rawr.camera.location.CaptureGps
import com.rawr.camera.settings.model.ColorRenderProfile
import com.rawr.camera.settings.model.DemosaicAlgorithm
import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.MultiframeTuning
import com.rawr.camera.settings.preferences.GpuDriverFileStore
import com.rawr.camera.settings.preferences.DcpFileStore
import com.rawr.camera.settings.preferences.LensProfileCodec
import com.rawr.camera.video.VideoImageSettings

/**
 * Application/UI coordinator only.
 *
 * Responsibility boundary:
 * - Kotlin: lifecycle, Surface handoff, semantic lens intent, diagnostics.
 * - Native C++: Camera2 NDK and the entire realtime imaging pipeline.
 */
class RawPreviewCoordinator(application: Application) : AutoCloseable {
    private val dcpDir = java.io.File(application.filesDir, DcpFileStore.DIR)
    private val native = NativePreviewEngine()
    private val nativeHandle: Long
    @Volatile private var currentVideoImageSettings: VideoImageSettings? = null
    fun videoImageSettingsSnapshot(): VideoImageSettings? = currentVideoImageSettings

    /** Serializes encoder-Surface ownership with camera/swapchain lifecycle. Call off Main. */
    fun setVideoSurface(surface: Surface?, width: Int = 1920, height: Int = 1080, bitDepth: Int = 10): Boolean =
        cameraWorker.submit<Boolean> {
            if (synchronized(this) { closed }) false
            else native.setVideoSurface(nativeHandle, surface, width, height, bitDepth)
        }.get()

    fun videoStats(): String = native.videoStats(nativeHandle)

    /**
     * Arms the idle-preview crop to the exact record window (recording output
     * size, 0 clears). Fire-and-forget like the other setters; the frame
     * submit path and focus/face mapping pick it up for subsequent frames.
     */
    fun setVideoPreviewCropOut(width: Int, height: Int) =
        postNative { native.setVideoPreviewCrop(nativeHandle, width, height) }

    fun clearVideoPreviewCrop() = postNative { native.setVideoPreviewCrop(nativeHandle, 0, 0) }

    /**
     * Keeps the recording pipeline for this size (0x0 = Open Gate) built so
     * the next recording starts without it. Native rebuilds it in the
     * background after camera reconfiguration and recordings.
     */
    fun prewarmVideo(width: Int, height: Int, bitDepth: Int) =
        postNative { native.prewarmVideo(nativeHandle, width, height, bitDepth) }

    /** Frees the prewarmed recording pipeline (leaving Video mode). */
    fun releaseVideoProcessing() = postNative { native.releaseVideoProcessing(nativeHandle) }

    fun setVideoImageSettings(settings: VideoImageSettings) {
        currentVideoImageSettings = settings
        postNative {
        native.setVideoImageSettings(nativeHandle, settings.lensShadingEnabled,
            settings.highlightEnabled, settings.highlightMethod, settings.highlightThreshold,
            settings.highlightCompression, settings.fccSteps, settings.defringeStrength,
            settings.waveletDenoiseStrength, settings.waveletDenoiseDetail,
            settings.waveletDenoiseLuma, settings.waveletDenoiseScales, settings.distortionEnabled)
        }
    }

    init {
        native.installCameraTagResolver(CameraTagResolver(application))
        native.installVendorCameraGate(VendorCameraGate(application))
        native.installJavaCameraBackend(JavaCameraBackend(application))
        nativeHandle =
            native.create(
                application.filesDir.absolutePath,
                application.applicationInfo.nativeLibraryDir,
                if (GpuPlatform.supportsCustomDriver) {
                    GpuDriverFileStore(application).activeDriverPath().orEmpty()
                } else {
                    ""
                }
            )
        native.setAssetManager(nativeHandle, application.assets)
    }

    // Presentation visibility only; camera eligibility lives in native policy.
    private var previewVisible = false
    private var initialSettingsSubmitted = false
    private var monitoringOverlayMode: Int = 0
    private var focusPeakingSensitivity: Float = 0.5f
    private var closed = false
    private var lastSurface: Surface? = null
    private var lastSurfaceRotation = -1
    private var lastSurfaceWidth = -1
    private var lastSurfaceHeight = -1

    // Surface/configuration calls and native policy actions share one lane.
    // Camera retirement and Vulkan waits must never block Android's Main thread.
    private val cameraWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // This is clock transport only: native owns all deadlines and recovery
    // decisions. It runs independently of UI telemetry collection.
    private var sessionClock: java.util.concurrent.ScheduledFuture<*>? = null
    private fun startSessionClock() {
        if (sessionClock != null) return
        sessionClock = cameraWorker.scheduleWithFixedDelay({
            if (!synchronized(this) { closed }) {
                try {
                    native.tickCameraSession(nativeHandle)
                } catch (e: Exception) {
                    Log.e(TAG, "native session tick failed", e)
                }
            }
        }, 100L, 100L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun onSurfaceAvailable(surface: Surface, displayRotationDegrees: Int, width: Int = -1, height: Int = -1) {
        if (closed) return
        // SurfaceView reports created/changed together. Reattach on size or
        // rotation changes so the native swapchain follows the actual window.
        if (lastSurface === surface && lastSurfaceRotation == displayRotationDegrees &&
            (width < 0 || height < 0 || (lastSurfaceWidth == width && lastSurfaceHeight == height))
        ) return
        lastSurface = surface
        lastSurfaceRotation = displayRotationDegrees
        if (width >= 0 && height >= 0) {
            lastSurfaceWidth = width
            lastSurfaceHeight = height
        }
        postNative {
            val current = synchronized(this) { lastSurface === surface }
            if (current) {
                val ok = native.setSurface(nativeHandle, surface, displayRotationDegrees)
                if (!ok) synchronized(this) {
                    if (lastSurface === surface) lastSurface = null
                    Log.i(TAG, "Native Vulkan surface setup failed")
                }
            }
        }
    }

    @Synchronized
    fun onSurfaceDestroyed() {
        if (closed || lastSurface == null) return
        lastSurface = null
        lastSurfaceRotation = -1
        lastSurfaceWidth = -1
        lastSurfaceHeight = -1
        // Native policy retires before detach, or preserves a still's lease.
        postNative { native.setSurface(nativeHandle, null, 0) }
    }

    @Synchronized
    fun start() {
        if (closed) return
        previewVisible = true
        native.setPreviewForeground(nativeHandle, true)
        postNative { native.tickCameraSession(nativeHandle) }
        startSessionClock()
    }

    /**
     * Atomic native intent: called directly, not posted to the camera worker lane, so it does not queue behind
     * Camera2 retirement or GPU waits running there. The lock is only held briefly (by [postNative], to enqueue).
     */
    @Synchronized
    fun setUiPresentationActive(active: Boolean) {
        if (!closed) native.setUiPresentationActive(nativeHandle, active)
    }

    @Synchronized
    fun stop() {
        if (closed) return
        previewVisible = false
        native.setPreviewForeground(nativeHandle, false)
        sessionClock?.cancel(false)
        sessionClock = null
        postNative { native.tickCameraSession(nativeHandle) }
        // Leaving the viewfinder (e.g. for Settings > Export) writes out the buffered audit files.
        postNative { native.flushDiagnostics(nativeHandle) }
    }

    /** Submitted after the initial persisted settings in the same FIFO lane. */
    @Synchronized
    fun setInitialConfigReady() {
        initialSettingsSubmitted = true
        postNative { native.setInitialConfigReady(nativeHandle) }
    }

    // Native atomic inputs: these cannot wait behind a queued stop or tick.
    @Synchronized
    fun beginStillCapture() {
        if (!closed) native.setStillCaptureInFlight(nativeHandle, true)
    }
    @Synchronized
    fun endStillCapture() {
        if (closed) return
        native.setStillCaptureInFlight(nativeHandle, false)
        postNative { native.tickCameraSession(nativeHandle) }
    }

    /**
     * Fire-and-forget native config push. Every setter takes SessionEngine
     * mu_ or the camera-controller mutex, both of which can be held for
     * seconds while cameraWorker retires/creates a Camera2 session
     * (vk waits, HAL drain). Calling them on Main ANR'd with Input
     * dispatching timeouts, so they all serialize on cameraWorker FIFO —
     * preserving submission order without ever blocking the UI thread.
     * Synchronous getters and still-capture entry points stay direct.
     */
    private inline fun postNative(crossinline call: () -> Unit) {
        synchronized(this) {
            if (closed) return
            cameraWorker.execute {
                if (synchronized(this) { closed }) return@execute
                try {
                    call()
                } catch (e: Exception) {
                    Log.e(TAG, "native setter failed", e)
                }
            }
        }
    }

    fun setOisEnabled(enabled: Boolean) = postNative { native.setOisEnabled(nativeHandle, enabled) }


    /** Semantic UI lens ID only; camera-ID/geometry mapping is native-owned. */
    fun selectLens(lensId: String) = postNative { native.selectLens(nativeHandle, lensId) }

    /** Enabled lenses in order; null restores the device's built-in lenses. */
    fun setLensProfiles(lenses: List<LensProfile>?) {
        val video = synchronized(lensSync) {
            lensProfiles = lenses
            lensVideoMode
        }
        pushLensProfiles(lenses, video)
    }

    /**
     * X14U: a lens with a video lens (the 1x's 16:9 "1xV") runs as it in video, except at 24 fps: the camera dies a
     * second after a fixed 24 fps range on that stream (diagnostics 20261125_112228, _131228), while the lens's own
     * 4:3 stream records 24 fps. `adb shell setprop debug.rawr.rec24_video_lens 1` keeps the video lens at 24 fps too.
     */
    private fun videoLensAt(fps: Int): Boolean = fps != 24 || DebugProps.int("rec24_video_lens", 0) == 1

    // X14U: a run-as lens (50 MP) runs only for the shot; the viewfinder stays on the lens's own 12 MP setup, where
    // AF and MF work (the full-size mode doesn't drive the focus motor; the lens keeps its position across the switch).
    @Volatile private var runAsForShot = false

    /** Runs the lenses as their run-as lens (true, for a 50 MP shot) or as themselves (false); restarts the camera. */
    fun setRunAsForShot(on: Boolean) {
        if (runAsForShot == on) return
        runAsForShot = on
        val (lenses, video) = synchronized(lensSync) { lensProfiles to lensVideoMode }
        pushLensProfiles(lenses, video)
    }


    // X14U: lenses with a video lens run as it in video mode, so the profile follows the capture mode.
    private val lensSync = Any()
    private var lensProfiles: List<LensProfile>? = null
    private var lensVideoMode = false
    private var lensVideoFps = 30

    private fun pushLensProfiles(lenses: List<LensProfile>?, video: Boolean) {
        val videoLens = synchronized(lensSync) { videoLensAt(lensVideoFps) }
        val json = lenses?.let { list ->
            // Lens options: the camera runs each lens with its selected choices applied (keeps the lens id).
            val resolved = com.rawr.camera.settings.preferences.LensOptionsCodec.forCamera(list, video, { lens, error ->
                Log.e(TAG, "lens options of ${lens.name} not applied", error)
            }, runAs = runAsForShot, videoLens = videoLens)
            LensProfileCodec.encode(resolved, enabledOnly = true, dcpDir = dcpDir)
        }
        postNative { native.setCameraProfile(nativeHandle, json ?: native.builtInCameraProfile()) }
    }


    fun setPreferredCameraId(cameraId: String?) = postNative { native.setPreferredCameraId(nativeHandle, cameraId) }

    fun setPreferredAccessRoute(route: String?) = postNative { native.setPreferredAccessRoute(nativeHandle, route) }

    fun setPreferredColorMode(mode: String?) = postNative { native.setPreferredColorMode(nativeHandle, mode) }

    fun setPipelineDiagnostic(mode: Int) = postNative { native.setPipelineDiagnostic(nativeHandle, mode) }

    fun setPersistentDiagnosticsEnabled(enabled: Boolean) =
        postNative { native.setPersistentDiagnosticsEnabled(nativeHandle, enabled) }

    fun setPersistZslRingEnabled(enabled: Boolean) =
        postNative { native.setPersistZslRingEnabled(nativeHandle, enabled) }



    /** Frames frozen; more than maxFrames when native folded the long frames into the burst. */
    fun prepareExperimentalMultiframe(maxFrames: Int): Int =
        native.prepareExperimentalMultiframe(nativeHandle, maxFrames.coerceIn(2, 30))

    fun cancelPreparedMultiframeCapture() = native.cancelPreparedMultiframeCapture(nativeHandle)

    fun setInternalTraceCaptureEnabled(enabled: Boolean) =
        postNative { native.setInternalTraceCaptureEnabled(nativeHandle, enabled) }

    fun setLensShadingCorrectionEnabled(enabled: Boolean) =
        postNative { native.setLensShadingCorrectionEnabled(nativeHandle, enabled) }

    fun setDistortionCorrectionEnabled(enabled: Boolean) =
        postNative { native.setDistortionCorrectionEnabled(nativeHandle, enabled) }

    fun setHighlightReconstructionEnabled(enabled: Boolean) =
        postNative { native.setHighlightReconstructionEnabled(nativeHandle, enabled) }

    fun setAntibandingMode(mode: Int) =
        postNative { native.setAntibandingMode(nativeHandle, mode) }

    fun setMaxAePostGain(gain: Float) =
        postNative { native.setMaxAePostGain(nativeHandle, gain) }

    fun setPostGainKneeWidthEv(widthEv: Float) =
        postNative { native.setPostGainKneeWidthEv(nativeHandle, widthEv) }

    fun setAutoKeyMode(mode: Int) = postNative { native.setAutoKeyMode(nativeHandle, mode) }
    /** Photo local tone mapping shadows EV; 0 = adaptive curve (always 0 in video). */
    fun setLocalTone(shadowsEv: Float, tuning: com.rawr.camera.settings.model.LocalToneTuning) = postNative {
        native.setLocalTone(nativeHandle, shadowsEv, tuning.highlightsEv, tuning.sigma, tuning.coarsestMip, tuning.boostLocalContrast)
    }
    fun setExpectedMergeFrames(frames: Int) = postNative { native.setExpectedMergeFrames(nativeHandle, frames.coerceIn(1, 30)) }

    fun setAutoMinFps(fps: Int) = postNative { native.setAutoMinFps(nativeHandle, fps) }
    /** X14U: applies to captures from the next shutter press (process-wide, no session needed). */
    fun setOutputAspect(aspect: Float) = native.setOutputAspect(aspect)
    fun setDigitalZoom(crop: Float, upscale: Boolean) = postNative { native.setDigitalZoom(nativeHandle, crop, upscale) }
    /** X14U "25MP": process-wide atomic, read at the shutter. */
    fun setFullSizeJpegHalf(on: Boolean) = native.setFullSizeJpegHalf(on)
    /** Straight to native atomics (sensor thread, many times a second). */
    fun setHorizonLock(roll: Float, crop: Float, natural: Boolean, viewRoll: Float, fit: Boolean) =
        native.setHorizonLock(roll, crop, natural, viewRoll, fit)
    /** X14U video crop: the sensor window recordings use (0x0 = the original size rule). */
    fun setVideoCropSource(width: Int, height: Int) = native.setVideoCropSource(width, height)
    fun setStabilizer(enabled: Boolean, focalNorm: Float) = native.setStabilizer(enabled, focalNorm)

    fun setVideoProfileDcp(on: Boolean) = native.setVideoProfileDcp(on)
    fun setCarryWhiteBalance(on: Boolean) = native.setCarryWhiteBalance(on)
    fun setFaceOptions(focus: Boolean, exposure: Boolean) = native.setFaceOptions(focus, exposure)
    fun setViewfinderHold(on: Boolean) = native.setViewfinderHold(on)
    fun heldNewFrames(): Long = native.heldNewFrames()
    fun heldNewFullSizeFrames(): Long = native.heldNewFullSizeFrames()
    fun setCompanionShot(on: Boolean) = native.setCompanionShot(on)
    fun heldNewCompanionFrames(): Long = native.heldNewCompanionFrames()
    fun pushStabilizerSample(timeNs: Long, pitch: Float, yaw: Float, roll: Float) =
        native.pushStabilizerSample(timeNs, pitch, yaw, roll)
    fun setAePriorityDisabled(disabled: Boolean) = postNative { native.setAePriorityDisabled(nativeHandle, disabled) }
    fun setRawrAutoExposure(s: com.rawr.camera.settings.model.RawrAeSettings) = postNative {
        native.setRawrAutoExposure(
            nativeHandle, s.enabled, s.nightLook.nativeId, 0, 0, 0L,
            s.maxShutter.ns, s.balance.ev, s.digitalGain.ev, s.speed.nativeId, s.metering.nativeId
        )
    }

    fun setVideoMode(isVideo: Boolean, fps: Int) {
        val lenses = synchronized(lensSync) {
            // A change to or from 24 fps swaps the 1x between its video lens and its own stream.
            val changed = lensVideoMode != isVideo || (isVideo && videoLensAt(lensVideoFps) != videoLensAt(fps))
            lensVideoMode = isVideo
            if (isVideo) lensVideoFps = fps
            // Also when a lens's photo and video DCP differ (video has its own DCP choice).
            lensProfiles?.takeIf {
                changed && it.any { lens -> !lens.videoLens.isNullOrBlank() || lens.image.dcp != lens.image.videoDcp }
            }
        }
        postNative { native.setVideoMode(nativeHandle, isVideo, fps) }
        if (lenses != null) pushLensProfiles(lenses, isVideo)
    }

    fun beginVideoFps(fps: Int): Boolean = cameraWorker.submit<Boolean> {
        if (synchronized(this) { closed }) false else native.setRecordingFps(nativeHandle, fps)
    }.get()

    fun endVideoFps() {
        cameraWorker.submit {
            if (!synchronized(this) { closed }) native.setRecordingFps(nativeHandle, 0)
        }.get()
    }

    fun setExperimentalZeroCopy(enabled: Boolean) =
        postNative { native.setExperimentalZeroCopy(nativeHandle, enabled) }
    // No restart needed: native allocates the ring's bridge copy in place and
    // the zero-copy reader usage no longer depends on the toggle.
    fun setExperimentalMultiframeEnabled(enabled: Boolean) =
        postNative { native.setExperimentalMultiframeEnabled(nativeHandle, enabled) }

    // No restart needed: the film engine is created lazily with a queue
    // wait-idle and the preview ingress is untouched.
    fun setFilmSim(enabled: Boolean) {
        postNative { native.setFilmSimEnabled(nativeHandle, enabled) }
    }

    // No restart needed: the divisor only changes per-frame record dims;
    // the arena stays sized for 2 and the engine is untouched.
    fun setViewfinderDivisor(divisor: Int) {
        postNative { native.setViewfinderDivisor(nativeHandle, divisor) }
    }

    // No restart needed: the flag is read at the next capture's render
    // block (and single-frame path); in-flight captures finish under the
    // mode they started with.
    fun setPersistentEngineEnabled(enabled: Boolean) {
        postNative { native.setPersistentEngineEnabled(nativeHandle, enabled) }
    }

    // Float/enum index contract: NativeEngineJni.setFilmSimLook comment.
    private var lastRequestedFilmValues: FloatArray? = null
    private var lastRequestedFilmEnums: IntArray? = null
    private var lastSubmittedFilmValues: FloatArray? = null
    private var lastSubmittedFilmEnums: IntArray? = null
    private var pendingFilmLook: Pair<FloatArray, IntArray>? = null
    private var pendingBakedFilmLook = false
    private val filmLookRunnable = Runnable {
        val look = synchronized(this) {
            pendingFilmLook.also {
                pendingFilmLook = null
                pendingBakedFilmLook = false
            }
        }
        if (look != null) submitFilmLook(look.first, look.second)
    }

    private fun submitFilmLook(values: FloatArray, enums: IntArray) {
        synchronized(this) {
            if (closed || (lastSubmittedFilmValues?.contentEquals(values) == true &&
                    lastSubmittedFilmEnums?.contentEquals(enums) == true)) return
            lastSubmittedFilmValues = values
            lastSubmittedFilmEnums = enums
        }
        postNative { native.setFilmSimLook(nativeHandle, values, enums) }
    }

    fun setFilmSimLook(values: FloatArray, enums: IntArray) {
        val valuesCopy = values.copyOf()
        val enumsCopy = enums.copyOf()
        val delayMs = synchronized(this) {
            if (closed || (lastRequestedFilmValues?.contentEquals(valuesCopy) == true &&
                    lastRequestedFilmEnums?.contentEquals(enumsCopy) == true)) return
            lastRequestedFilmValues = valuesCopy
            lastRequestedFilmEnums = enumsCopy
            // Stock, paper, colorspace, spectral method, grain model and
            // camera filters rebuild GPU resources. A vertical picker can
            // cross many in one gesture; apply only its latest stop. Live
            // numeric/effect edits are coalesced to roughly one per frame.
            val bakedChange = lastSubmittedFilmEnums?.let { previous ->
                intArrayOf(0, 1, 2, 3, 4, 9, 15, 16).any { index ->
                    index < previous.size && index < enumsCopy.size && previous[index] != enumsCopy[index]
                } || (lastSubmittedFilmValues?.let { old ->
                    old.size > 43 && valuesCopy.size > 43 &&
                        (old[42] != valuesCopy[42] || old[43] != valuesCopy[43])
                } == true)
            } == true
            val alreadySubmitted = lastSubmittedFilmValues?.contentEquals(valuesCopy) == true &&
                lastSubmittedFilmEnums?.contentEquals(enumsCopy) == true
            val delay = when {
                !previewVisible || !initialSettingsSubmitted || lastSubmittedFilmValues == null || alreadySubmitted -> 0L
                bakedChange || pendingBakedFilmLook -> FILM_LOOK_DEBOUNCE_MS
                else -> FILM_LIVE_UPDATE_MS
            }
            pendingFilmLook = if (delay == 0L) null else valuesCopy to enumsCopy
            pendingBakedFilmLook = delay == FILM_LOOK_DEBOUNCE_MS
            delay
        }
        mainHandler.removeCallbacks(filmLookRunnable)
        if (delayMs != 0L) mainHandler.postDelayed(filmLookRunnable, delayMs)
        else submitFilmLook(valuesCopy, enumsCopy)
    }

    fun setCpuRawCopyProbeFrames(frames: Int) = postNative { native.setCpuRawCopyProbeFrames(nativeHandle, frames) }

    fun setMonitoringOverlay(mode: Int) {
        val sensitivity = synchronized(this) {
            monitoringOverlayMode = mode
            focusPeakingSensitivity
        }
        postNative { native.setMonitoringOverlay(nativeHandle, mode, sensitivity) }
    }

    fun setFocusPeakingSensitivity(sensitivity: Float) {
        val (mode, clamped) = synchronized(this) {
            focusPeakingSensitivity = sensitivity.coerceIn(0f, 1f)
            monitoringOverlayMode to focusPeakingSensitivity
        }
        postNative { native.setMonitoringOverlay(nativeHandle, mode, clamped) }
    }

    fun setFocusLoupe(values: FloatArray?, meter: Boolean) = postNative { native.setFocusLoupe(nativeHandle, values?.copyOf(), meter) }
    fun focusMeterValue(): Float = native.focusMeterValue(nativeHandle)

    fun setScopePresentationState(types: IntArray, modes: IntArray, rotations: IntArray, rects: FloatArray) =
        postNative {
            native.setScopePresentationState(
                nativeHandle, types.copyOf(), modes.copyOf(), rotations.copyOf(), rects.copyOf()
            )
        }

    @Volatile var captureDeviceRotationDegrees: Int = 0
        private set

    fun setCaptureDeviceRotationDegrees(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        captureDeviceRotationDegrees = normalized
        // Physical posture is NOT Surface/display rotation. The Activity is portrait-locked,
        // so feeding this into preview presentation rotates the image inside the fixed Surface.
        // It is a separate spatial-orientation input for waveform measurement and DNG only.
        // Sensor-rate callback: must not block Main on the native mutex.
        postNative { native.setScopeDeviceRotationDegrees(nativeHandle, normalized) }
    }

    private fun subsamplingCode(id: String): Int =
        when (id) {
            "jpeg.444" -> 0
            "jpeg.422" -> 1
            else -> 2
        }

    private fun demosaicCode(algorithm: DemosaicAlgorithm): Int =
        when (algorithm) {
            DemosaicAlgorithm.Amaze -> 4
            DemosaicAlgorithm.Rcd -> 0
            DemosaicAlgorithm.Vng4 -> 2
            DemosaicAlgorithm.DualRcdVng4 -> 3
            DemosaicAlgorithm.DualAmazeVng4 -> 5
        }

    private fun dngCompressionCode(id: String): Int =
        when (id) {
            "dng.uncompressed" -> 1
            else -> 0
        }

    fun requestRawStillCapture(        dngOutputFd: Int,
        jpegOutputFd: Int,
        jpegQuality: Int,
        jpegChromaSubsamplingId: String,
        dngCompressionId: String,
        pipelineDiagnosticsEnabled: Boolean,
        colorRenderProfile: ColorRenderProfile,
        importedLutProfileId: String,
        rendererDisplayName: String,
        demosaicAlgorithm: DemosaicAlgorithm,
        dualAutoContrast: Boolean,
        dualContrastPercent: Float,
        fccSteps: Int,
        lensShadingCorrectionEnabled: Boolean,
        highlightReconstructionEnabled: Boolean,
        wallClockMillis: Long,
        utcOffsetMinutes: Int,
        deviceMake: String,
        deviceModel: String,
        dngDisplayName: String,
        jpegDisplayName: String,
        distortionCorrectionEnabled: Boolean,
        filmDescription: String?,
        defringeEnabled: Boolean,
        defringeStrength: Float,
        defringeEdgeThreshold: Float,
        defringeLumaFloor: Float,
        // Collapsed denoise spec (see DenoiseConfig.toModesArray /
        // toStrengthsArray). Forwarded straight to the JNI boundary.
        denoiseModes: IntArray,
        denoiseStrengths: FloatArray,
        captureRecipe: String = "",
        captureTone: FloatArray? = null,
        captureFilmValues: FloatArray? = null,
        captureFilmEnums: IntArray? = null,
        captureFilmEnabled: Boolean = false,
        ultraHdrEnabled: Boolean = false,
        ultraHdrGainmapQuality: Int = 95,
        gps: CaptureGps? = null,
        level: CaptureLevel? = null
    ): Long {
        val subsampling = subsamplingCode(jpegChromaSubsamplingId)
        val demosaic = demosaicCode(demosaicAlgorithm)
        return native.requestRawStillCapture(
            nativeHandle,
            dngOutputFd,
            jpegOutputFd,
            jpegQuality,
            subsampling,
            pipelineDiagnosticsEnabled,
            colorRenderProfile.nativeId,
            importedLutProfileId,
            rendererDisplayName,
            demosaic,
            dualAutoContrast,
            dualContrastPercent.coerceIn(0f, 100f),
            fccSteps.coerceIn(1, 8),
            lensShadingCorrectionEnabled,
            highlightReconstructionEnabled,
            captureDeviceRotationDegrees,
            wallClockMillis,
            utcOffsetMinutes,
            deviceMake,
            deviceModel,
            dngDisplayName,
            jpegDisplayName,
            distortionCorrectionEnabled,
            filmDescription,
            defringeEnabled,
            defringeStrength.coerceIn(0f, 1f),
            defringeEdgeThreshold.coerceIn(0.005f, 0.2f),
            defringeLumaFloor.coerceIn(0f, 0.5f),
            denoiseModes,
            denoiseStrengths,
            captureRecipe, captureTone, captureFilmValues, captureFilmEnums, captureFilmEnabled,
            ultraHdrEnabled, ultraHdrGainmapQuality.coerceIn(1, 100),
            dngCompressionCode(dngCompressionId), gps, level?.toNative()
        )
    }

    fun startPreparedMultiframeCapture(
        baseDngOutputFd: Int,
        mergedDngOutputFd: Int,
        jpegOutputFd: Int,
        jpegQuality: Int,
        jpegChromaSubsamplingId: String,
        dngCompressionId: String,
        pipelineDiagnosticsEnabled: Boolean,
        colorRenderProfile: ColorRenderProfile,
        importedLutProfileId: String,
        rendererDisplayName: String,
        demosaicAlgorithm: DemosaicAlgorithm,
        dualAutoContrast: Boolean,
        dualContrastPercent: Float,
        fccSteps: Int,
        lensShadingCorrectionEnabled: Boolean,
        highlightReconstructionEnabled: Boolean,
        wallClockMillis: Long,
        utcOffsetMinutes: Int,
        deviceMake: String,
        deviceModel: String,
        baseDngDisplayName: String,
        mergedDngDisplayName: String,
        jpegDisplayName: String,
        dumpRzslRequested: Boolean,
        multiframeTuning: MultiframeTuning,
        multiframeBaseFrameMode: com.rawr.camera.settings.model.MultiframeBaseFrameMode,
        multiframeChromaDenoise: Boolean = false,
        multiframeDetailRestore: Boolean = false,
        distortionCorrectionEnabled: Boolean,
        filmDescription: String?,
        defringeEnabled: Boolean,
        defringeStrength: Float,
        defringeEdgeThreshold: Float,
        defringeLumaFloor: Float,
        // Collapsed denoise spec (see DenoiseConfig.toModesArray /
        // toStrengthsArray). Forwarded straight to the JNI boundary.
        denoiseModes: IntArray,
        denoiseStrengths: FloatArray,
        captureRecipe: String = "",
        captureTone: FloatArray? = null,
        captureFilmValues: FloatArray? = null,
        captureFilmEnums: IntArray? = null,
        captureFilmEnabled: Boolean = false,
        ultraHdrEnabled: Boolean = false,
        ultraHdrGainmapQuality: Int = 95,
        gps: CaptureGps? = null,
        level: CaptureLevel? = null
    ): Long {
        val subsampling = subsamplingCode(jpegChromaSubsamplingId)
        val demosaic = demosaicCode(demosaicAlgorithm)
        return native.startPreparedMultiframeCapture(
            nativeHandle,
            baseDngOutputFd,
            mergedDngOutputFd,
            jpegOutputFd,
            jpegQuality,
            subsampling,
            pipelineDiagnosticsEnabled,
            colorRenderProfile.nativeId,
            importedLutProfileId,
            rendererDisplayName,
            demosaic,
            dualAutoContrast,
            dualContrastPercent.coerceIn(0f, 100f),
            fccSteps.coerceIn(1, 8),
            lensShadingCorrectionEnabled,
            highlightReconstructionEnabled,
            captureDeviceRotationDegrees,
            wallClockMillis,
            utcOffsetMinutes,
            deviceMake,
            deviceModel,
            baseDngDisplayName,
            mergedDngDisplayName,
            jpegDisplayName,
            dumpRzslRequested,
            // Tuning contract + chroma-denoise flag (JNI index 22) + merge values (23-28) + Rexture detail restoration (29).
            multiframeTuning.sanitized().let { tuning ->
                tuning.nativeValues() + (if (multiframeChromaDenoise) 1f else 0f) + tuning.nativeMergeValues() +
                    (if (multiframeDetailRestore) 1f else 0f)
            },
            multiframeBaseFrameMode.nativeId,
            distortionCorrectionEnabled,
            filmDescription,
            defringeEnabled,
            defringeStrength.coerceIn(0f, 1f),
            defringeEdgeThreshold.coerceIn(0.005f, 0.2f),
            defringeLumaFloor.coerceIn(0f, 0.5f),
            denoiseModes,
            denoiseStrengths,
            captureRecipe, captureTone, captureFilmValues, captureFilmEnums, captureFilmEnabled,
            ultraHdrEnabled, ultraHdrGainmapQuality.coerceIn(1, 100),
            dngCompressionCode(dngCompressionId), gps, level?.toNative()
        )
    }

    fun pollDngWriteCompletion(): String = native.pollDngWriteCompletion(nativeHandle)

    /** An accepted still still needs the camera session (frames due or not yet copied out). */
    fun stillCaptureHoldsCamera(): Boolean = native.stillCaptureHoldsCamera(nativeHandle)

    /** X14U Burst pre-capture (native PrecaptureRing); maxFrames 0 = off. */
    fun armPrecapture(maxFrames: Int, intervalMs: Int) {
        if (nativeHandle != 0L) native.armPrecapture(nativeHandle, maxFrames, intervalMs)
    }
    fun precaptureHeld(): Int = if (nativeHandle != 0L) native.precaptureHeld(nativeHandle) else 0
    fun freezePrecapture(): Int = if (nativeHandle != 0L) native.freezePrecapture(nativeHandle) else 0
    fun beginPrecaptureDrain(): Int = if (nativeHandle != 0L) native.beginPrecaptureDrain(nativeHandle) else 0
    fun endPrecapture() {
        if (nativeHandle != 0L) native.endPrecapture(nativeHandle)
    }

    /** X14U Burst: background development waits while the shutter is held. */
    fun setStillDevelopPaused(paused: Boolean) = native.setStillDevelopPaused(paused)

    /** X14U Burst: still RAW buffers allocated ahead so a burst starts at full speed. */
    fun prewarmStillBuffers(count: Int) {
        if (nativeHandle != 0L) native.prewarmStillBuffers(nativeHandle, count)
    }

    fun pollJpegWriteCompletion(): String = native.pollJpegWriteCompletion(nativeHandle)

    @Synchronized
    fun canRecoverStills(): Boolean = !closed && native.canRecoverStills(nativeHandle)

    fun recoverStill(name: String, multiframe: Boolean, dng: Int, merged: Int, jpeg: Int): Long =
        native.recoverStill(nativeHandle, name, multiframe, dng, merged, jpeg)

    fun runHighlightReplay(inputPath: String): String = cameraWorker.submit<String> {
        if (synchronized(this) { closed }) "HIGHLIGHT_REPLAY_FAIL native=closed"
        else native.runHighlightReplay(nativeHandle, inputPath)
    }.get()

    fun cameraControlSnapshot(): String = native.cameraControlSnapshot(nativeHandle)

    fun videoRotationDegrees(deviceRotationDegrees: Int): Int = cameraWorker.submit<Int> {
        if (synchronized(this) { closed }) -1 else native.videoRotationDegrees(nativeHandle, deviceRotationDegrees)
    }.get()

    fun faceDetectionsSnapshot(): String = native.faceDetectionsSnapshot(nativeHandle)

    fun setExposureMode(mode: Int) = postNative { native.setExposureMode(nativeHandle, mode) }

    fun setManualExposureTimeNs(exposureTimeNs: Long) =
        postNative { native.setManualExposureTimeNs(nativeHandle, exposureTimeNs) }
    fun setShutterAngleDegrees(degrees: Double) =
        postNative { native.setShutterAngleDegrees(nativeHandle, degrees) }

    fun setManualSensitivity(sensitivity: Int) = postNative { native.setManualSensitivity(nativeHandle, sensitivity) }

    fun setExposureCompensationSteps(steps: Int) =
        postNative { native.setExposureCompensationSteps(nativeHandle, steps) }

    fun setWhiteBalanceMode(mode: Int, requestId: Long) = postNative { native.setWhiteBalanceMode(nativeHandle, mode, requestId) }

    fun setWhiteBalanceTempTint(temperatureK: Int, tint: Int, editedAxes: Int, requestId: Long) =
        postNative { native.setWhiteBalanceTempTint(nativeHandle, temperatureK, tint, editedAxes, requestId) }

    /** V2 lock entry: manual mode pinned at the given point, no AWB re-seed. */
    fun setWhiteBalanceLocked(temperatureK: Int, tint: Int, requestId: Long) =
        postNative { native.setWhiteBalanceLocked(nativeHandle, temperatureK, tint, requestId) }

    fun setSpotAeTarget(active: Boolean, x: Float, y: Float) =
        postNative { native.setSpotAeTarget(nativeHandle, active, x, y) }

    fun setFocusMode(mode: Int, x: Float, y: Float, requestId: Long) =
        postNative { native.setFocusMode(nativeHandle, mode, x, y, requestId) }

    fun focusAt(x: Float, y: Float, requestId: Long) = postNative { native.focusAt(nativeHandle, x, y, requestId) }

    /** Back to full auto: drops the tap-AF region/trigger so continuous AF resumes. */
    fun clearFocus(requestId: Long) = postNative { native.clearTapAf(nativeHandle, requestId) }

    fun setManualFocusNormalized(normalized: Float, requestId: Long) =
        postNative { native.setManualFocusNormalized(nativeHandle, normalized, requestId) }

    fun setQuickToneNativeValue(target: Int, value: Float) =
        postNative { native.setQuickToneNativeValue(nativeHandle, target, value) }

    fun setColorRenderProfile(profile: Int, importedLutProfileId: String?) =
        postNative { native.setColorRenderProfile(nativeHandle, profile, importedLutProfileId.orEmpty()) }

    fun setColorRenderProfile(profile: ColorRenderProfile, importedLutProfileId: String?) =
        postNative { native.setColorRenderProfile(nativeHandle, profile.nativeId, importedLutProfileId.orEmpty()) }

    fun setAgxParameters(values: com.rawr.camera.settings.model.AgxValues) =
        postNative { native.setAgxParameters(nativeHandle, values.toNativeArray()) }

    fun setLutStrength(strength: Float) = postNative { native.setLutStrength(nativeHandle, strength) }

    fun setTonemapParameters(tone: com.rawr.camera.model.ImageToneState) {
        val contract = com.rawr.camera.model.TonemapControlContract
        setTonemapParameters(tone.renderExposure, contract.blackPointNativeFromUi(tone.blacks),
            contract.shadowNativeFromUi(tone.shadows), contract.midtoneNativeFromUi(tone.midtones),
            contract.contrastNativeFromUi(tone.contrast), contract.whitePointNativeFromUi(tone.whites),
            contract.highlightNativeFromUi(tone.highlights), tone.saturation, tone.vibrance)
    }

    fun setTonemapParameters(
        exposureEV: Float,
        blackPointEV: Float,
        shadowLiftEV: Float,
        midtoneLiftEV: Float,
        contrast: Float,
        whitePointEV: Float,
        highlightBiasEV: Float,
        saturation: Float,
        vibrance: Float
    ) = postNative {
        native.setTonemapParameters(
            nativeHandle,
            exposureEV,
            blackPointEV,
            shadowLiftEV,
            midtoneLiftEV,
            contrast,
            whitePointEV,
            highlightBiasEV,
            saturation,
            vibrance
        )
    }

    override fun close() {
        val alreadyClosed = synchronized(this) {
            val was = closed
            closed = true
            previewVisible = false
            if (!was) native.setPreviewForeground(nativeHandle, false)
            was
        }
        if (alreadyClosed) return
        sessionClock?.cancel(false)
        mainHandler.removeCallbacks(filmLookRunnable)
        // Drain queued start/stop/bounce tasks before destroy so no
        // in-flight setCameraActive can UAF the handle. Retire the session
        // before detaching the surface, same order as onSurfaceDestroyed.
        cameraWorker.execute {
            try {
                native.setCameraActive(nativeHandle, false)
            } catch (e: Exception) {
                Log.e(TAG, "setCameraActive(false) on close failed", e)
            }
            try {
                native.setSurface(nativeHandle, null, 0)
            } catch (e: Exception) {
                Log.e(TAG, "setSurface(null) on close failed", e)
            } finally {
                native.destroy(nativeHandle)
            }
        }
        cameraWorker.shutdown()
    }

    private companion object {
        const val TAG = "RawrCamCoordinator"
        const val FILM_LOOK_DEBOUNCE_MS = 180L
        const val FILM_LIVE_UPDATE_MS = 16L
    }
}
