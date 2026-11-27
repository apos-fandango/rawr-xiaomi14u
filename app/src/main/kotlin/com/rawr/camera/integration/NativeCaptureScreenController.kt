package com.rawr.camera.integration

import com.rawr.camera.architecture.*
import com.rawr.camera.model.*
import com.rawr.camera.model.TonemapControlContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production capture-screen application adapter.
 *
 * Kotlin owns only UI/application state and semantic dispatch. Camera legality, Camera2
 * request/result state, AF regions and manual controls are native C++ responsibilities.
 */
class NativeCaptureScreenController(
    private val coordinator: RawPreviewCoordinator,
    private val requestStillCapture: (CaptureOutputFormat) -> Long,
    private val onCaptureQueued: (CaptureOutputFormat) -> Unit = {},
    private val pollStillCompletion: () -> List<StillCaptureCoordinator.CompletionEvent>,
    private val onToneScrubbed: (ToneParameter, Int) -> Unit = { _, _ -> },
    private val onRenderExposureScrubbed: (Int) -> Unit = {},
    private val onAgxControl: (com.rawr.camera.settings.model.AgxParameter, Float?) -> Unit = { _, _ -> },
    private val onCapturePreferencesChanged: (CaptureUiState, CaptureUiState) -> Unit = { _, _ -> },
    private val videoLocked: () -> Boolean = { false },
    private val onRawCpuIngress: () -> Unit = {},
    /** A lens switch was accepted (persisted as the next launch's lens). */
    private val onLensSelected: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val foregroundToken: () -> Any?,
    /** X14U Burst: a run (with its pre-capture frames) was handed to saving. */
    private val onBurstFinished: (BurstRun) -> Unit = {}
) : CaptureScreenController {
    private val mutableState =
        MutableStateFlow(
            CaptureUiState(
                capabilities = NativeCapabilityProjection.pending(),
                // Empty until native reports the lens it opened.
                selectedLensId = "",
                exposureApplied = ExposureAppliedState.pending(),
                focus = FocusUiState(status = TargetStatus.Hidden)
            )
        )
    override val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val captureQueue = StillCaptureDispatchQueue(scope, onCaptureQueued, ::captureStill,
        onRejected = { format, error ->
            if (error != null) android.util.Log.e("RawrCamCapture", "Could not start photo processing", error)
            if (state.value.bracketHoldPresses > 0) update { it.copy(bracketHoldPresses = it.bracketHoldPresses - 1) }
            capturePresentation.rejected(format.jpeg)
        })
    private val overlayTriggers = OverlayTriggerManager(scope, coordinator::setMonitoringOverlay)
    private val focusRequestIds = java.util.concurrent.atomic.AtomicLong()
    private val snapshotPresentation = NativeCaptureSnapshotPresentation()
    private val whiteBalanceRequestIds = java.util.concurrent.atomic.AtomicLong()
    private val capturePresentation = StillCapturePresentation(scope, ::update)
    private val selfTimer = CaptureSelfTimer(scope, state, ::update, ::enqueueStillCapture, foregroundToken)
    // Once per process: the CPU path is a device/stream property, not a per-frame event.
    private var rawCpuIngressNotified = false

    init {
        // Native starts on the first lens of its profile; the snapshot's
        // profile lenses then reconcile selectedLensId.
        scope.launch {
            while (isActive) {
                val snapshot = NativeCameraUiSnapshot.parse(coordinator.cameraControlSnapshot())
                if (snapshot != null) {
                    if (snapshot.rawCpuIngress && !rawCpuIngressNotified) {
                        rawCpuIngressNotified = true
                        onRawCpuIngress()
                    }
                    applyNativeSnapshot(snapshot)
                    val focus = state.value.focus
                    overlayTriggers.autofocusStatus(focus.status, focus.mode == FocusMode.Mf)
                }
                updateFaces(NativeCameraUiSnapshot.parseFaces(coordinator.faceDetectionsSnapshot()))

                // Applied exposure is display telemetry, not part of the AE loop.
                // 10 Hz is responsive enough and avoids unnecessary JNI/JSON/UI churn;
                // a bracket hold, a long exposure, or preview recovery polls faster
                // so the shutter ring start/end is shown promptly.
                delay(if (state.value.bracketHoldActive || state.value.restoringPreview ||
                    state.value.longExposure.active) 33 else 100)
            }
        }
        scope.launch {
            while (isActive) {
                captureQueue.poll { capturePresentation.completed(pollStillCompletion()) }
                // A finished capture is only published when this loop sees it: poll fast while one is in flight
                // (the 100 ms idle period added 50 ms on average to every capture), slowly otherwise.
                delay(if (state.value.pendingCaptures.any { !it.terminal }) 15 else 100)
            }
        }
    }

    override fun dispatch(action: CaptureAction) {
        if (videoLocked() && (action is SetCaptureMode || action is ToggleVideoLog ||
                action is CycleVideoResolution || action is SetVideoCrop || action is CycleVideoFps)) return
        if ((action is ScrubTone || action is ScrubRenderExposure || action is SetAgxControl ||
                action is SetRenderExposureTenths) &&
            state.value.captureMode == CaptureMode.Video && state.value.videoLogEnabled) return
        when (action) {
            is PresentationCaptureAction -> {
                val before = state.value
                update { CaptureReducer.reduce(it, action) }
                if (action is ToggleOverlayArmed || action is ToggleFalseColor ||
                    action is RestoreCapturePreferences
                ) {
                    syncOverlayArming()
                }
                selfTimer.reconcile()
                if (action is SetCaptureMode || action is CycleVideoFps || action is CycleVideoResolution ||
                    action is SetVideoCrop || action is RestoreCapturePreferences) {
                    val current = state.value
                    coordinator.setVideoMode(current.captureMode == CaptureMode.Video, current.videoFps)
                }
                if (action !is RestoreCapturePreferences && action.isDurableCapturePreference()) {
                    onCapturePreferencesChanged(before, state.value)
                }
            }

            is ApplicationCaptureAction -> {
                dispatchApplication(action)
            }
        }
    }

    private fun dispatchApplication(action: ApplicationCaptureAction) {
        when (action) {
            is SetExposureMode -> {
                setExposureMode(action.mode)
            }

            is SetWhiteBalanceMode -> {
                setWhiteBalanceMode(action.mode)
            }

            is SetWhiteBalanceTempTint -> {
                setWhiteBalanceTempTint(action.temperatureK, action.tint)
            }

            is LockWhiteBalance -> {
                lockWhiteBalance(action.temperatureK, action.tint)
            }

            is SetExposureCandidate -> {
                setExposureCandidate(action)
            }

            is SyncToneControls -> {
                update {
                    it.copy(
                        blacks = action.blacks.coerceIn(-100, 100),
                        shadows = action.shadows.coerceIn(-100, 100),
                        contrast = action.contrast.coerceIn(-100, 100),
                        midtones = action.midtones.coerceIn(-100, 100),
                        highlights = action.highlights.coerceIn(-100, 100),
                        whites = action.whites.coerceIn(-100, 100),
                        saturation = action.saturation.coerceIn(-100, 100),
                        vibrance = action.vibrance.coerceIn(-100, 100),
                        renderExposureTenths = action.renderExposureTenths.coerceIn(
                            TonemapControlContract.EXPOSURE_MIN_TENTHS,
                            TonemapControlContract.EXPOSURE_MAX_TENTHS
                        )
                    )
                }
            }

            // Persisted by CaptureViewModel; LiveTonemapState applies it natively.
            is SetAgxControl -> onAgxControl(action.parameter, action.value)

            is SetRenderExposureTenths ->
                dispatchApplication(ScrubRenderExposure(action.tenths - state.value.renderExposureTenths))

            is ScrubRenderExposure -> {
                val next = CaptureTransitions.scrubRenderExposure(state.value, action.deltaTenths)
                mutableState.value = next
                // Persisted by CaptureViewModel; the settings publish applies the
                // complete ImageToneState to TonemapEngine.
                onRenderExposureScrubbed(next.renderExposureTenths)
            }

            is ScrubTone -> {
                val next = CaptureTransitions.scrubTone(state.value, action.target, action.delta)
                mutableState.value = next
                val value = next.toneValueFor(action.target)
                // Preserve the low-latency native shortcut for the two original quick controls.
                // All parameters are persisted/published by CaptureViewModel, which applies the
                // complete ImageToneState atomically to TonemapEngine.
                when (action.target) {
                    ToneParameter.Shadows -> {
                        coordinator.setQuickToneNativeValue(
                            0,
                            TonemapControlContract.shadowNativeFromUi(value.toFloat())
                        )
                    }

                    ToneParameter.Highlights -> {
                        coordinator.setQuickToneNativeValue(
                            1,
                            TonemapControlContract.highlightNativeFromUi(value.toFloat())
                        )
                    }

                    else -> {
                        Unit
                    }
                }
                onToneScrubbed(action.target, value)
                if (action.target == ToneParameter.Shadows || action.target == ToneParameter.Blacks) {
                    overlayTriggers.pulseShadow()
                }
            }

            is SelectLens -> {
                selectLens(action.id)
            }

            is SetManualFocus -> {
                setManualFocus(action.normalized)
            }

            is ApplyCameraCapabilities -> {
                Unit
            }

            // Native snapshots are the only production capability authority.
            is FocusAt -> {
                focusAt(action.point)
            }

            Refocus -> {
                focusAt(state.value.focus.target)
            }

            ClearAutofocus -> {
                clearAutofocus()
            }

            is SetFocusMode -> {
                setFocusMode(action.mode)
            }

            is ActivateSpotAe -> {
                if (state.value.exposureControl.mode == ExposureMode.Auto) {
                    update { CaptureTransitions.beginSpotAe(it, action.point) }
                    coordinator.setSpotAeTarget(true, action.point.x, action.point.y)
                }
            }

            is MoveSpotAe -> {
                if (state.value.exposureControl.mode == ExposureMode.Auto && state.value.spotAe.active) {
                    update { CaptureTransitions.moveSpotAe(it, action.point) }
                    coordinator.setSpotAeTarget(true, action.point.x, action.point.y)
                }
            }

            FinishSpotAeMove -> {
                if (state.value.exposureControl.mode == ExposureMode.Auto && state.value.spotAe.active) {
                    val target = state.value.spotAe.target
                    coordinator.setSpotAeTarget(true, target.x, target.y)
                }
            }

            CancelSpotAe -> {
                update(CaptureTransitions::cancelSpotAe)
                coordinator.setSpotAeTarget(false, 0.5f, 0.5f)
            }

            TriggerCapture -> {
                selfTimer.trigger()
            }

            com.rawr.camera.architecture.TriggerCaptureNow -> {
                foregroundToken()?.let { enqueueStillCapture(it) }
            }

            is StartBurst -> startBurst(action)
            StopBurst -> stopBurst()
            is SyncBurstLastCount -> {
                if (!state.value.burst.running) update { it.copy(burst = it.burst.copy(lastCount = action.count)) }
            }
            EndBurstMode -> {
                stopBurst()
                coordinator.setStillDevelopPaused(false)
                com.rawr.camera.storage.CaptureFileNames.endBurst()
                update { it.copy(burst = it.burst.copy(lastCount = 0, stoppedFull = false)) }
            }
        }
    }

    private fun PresentationCaptureAction.isDurableCapturePreference(): Boolean = when (this) {
        CycleOutputFormat, ToggleWaveformMode -> true
        is ToggleOverlayArmed, is ToggleFalseColor, is ToggleScope, is SetGrid, is SetSingleScope -> true
        ToggleFilmSim, ToggleMultiframe -> true
        is SetCaptureLayout -> true
        is SetCaptureMode, CycleVideoResolution, is SetVideoCrop, CycleVideoFps, ToggleVideoLog -> true
        CycleSelfTimer, is SetCaptureSelfTimer -> true
        else -> false
    }

    private fun syncOverlayArming() {
        overlayTriggers.setArmed(state.value.armedOverlays)
        overlayTriggers.setFalseColor(state.value.falseColorManual)
    }

    private fun setExposureMode(mode: ExposureMode) {
        if (mode !in state.value.capabilities.supportedExposureModes) return
        // Native camera state is authoritative for mode acceptance. The next
        // snapshot performs the UI transition only after Camera2/RAW-AE ownership
        // accepted the requested semantic mode.
        coordinator.setExposureMode(mode.ordinal)
    }

    private fun setWhiteBalanceMode(mode: WhiteBalanceMode) {
        val current = state.value
        val next = CaptureTransitions.requestWhiteBalanceMode(current, mode)
        if (next == current) return
        val requestId = whiteBalanceRequestIds.incrementAndGet()
        update {
            if (mode == WhiteBalanceMode.ManualTempTint) CaptureTransitions.requestWhiteBalanceMode(it, mode)
            else CaptureTransitions.snapWhiteBalanceDisplayToMode(it, mode, it.whiteBalanceTemperatureK, it.whiteBalanceTint)
        }
        coordinator.setWhiteBalanceMode(mode.awbValue, requestId)
    }

    private fun setWhiteBalanceTempTint(temperatureK: Int, tint: Int) {
        val current = state.value
        val next = CaptureTransitions.setWhiteBalanceTempTint(current, temperatureK, tint)
        if (next == current) return
        // Report gesture intent against what the user saw, not the native
        // request coordinate. Native seeds the untouched axis from live AWB.
        val editedAxes = if (current.whiteBalanceMode == WhiteBalanceMode.ManualTempTint) 3 else
            (if (temperatureK != current.whiteBalanceTemperatureK) 1 else 0) or
                (if (tint != current.whiteBalanceTint) 2 else 0)
        val requestId = whiteBalanceRequestIds.incrementAndGet()
        update { CaptureTransitions.setWhiteBalanceTempTint(it, temperatureK, tint) }
        coordinator.setWhiteBalanceTempTint(temperatureK, tint, editedAxes, requestId)
    }

    private fun lockWhiteBalance(temperatureK: Int, tint: Int) {
        val current = state.value
        val next = CaptureTransitions.lockWhiteBalance(current, temperatureK, tint)
        if (next == current) return
        val requestId = whiteBalanceRequestIds.incrementAndGet()
        update { CaptureTransitions.lockWhiteBalance(it, temperatureK, tint) }
        coordinator.setWhiteBalanceLocked(temperatureK, tint, requestId)
    }

    private fun setExposureCandidate(action: SetExposureCandidate) {
        val p = snapshotPresentation.exposureProjectionFor(state.value) ?: return
        when (action.parameter) {
            ExposureParameter.Iso -> {
                p.isoValues[action.candidateId]?.let(coordinator::setManualSensitivity)
            }

            ExposureParameter.Shutter -> {
                p.shutterAnglesDeg[action.candidateId]?.let(coordinator::setShutterAngleDegrees)
                    ?: p.shutterValuesNs[action.candidateId]?.let(coordinator::setManualExposureTimeNs)
            }

            ExposureParameter.Ev -> {
                p.evSteps[action.candidateId]?.let(coordinator::setExposureCompensationSteps)
            }
        } ?: return
        update { CaptureTransitions.requestExposureCandidate(it, action.parameter, action.candidateId) }
        overlayTriggers.pulseRawHighlight()
    }

    private fun selectLens(lensId: String) {
        if (state.value.capabilities.lenses
                .none { it.id == lensId }
        ) {
            return
        }
        snapshotPresentation.invalidate()
        // Invalidate pending gestures and acknowledge their clear before the
        // new lens is selected. Older tap snapshots cannot reopen its box.
        coordinator.clearFocus(focusRequestIds.incrementAndGet())
        selfTimer.cancel()
        update { current ->
            val pending = CaptureTransitions.applyCameraCapabilities(
                current, NativeCapabilityProjection.pending(current.capabilities.lenses)
            )
            CaptureTransitions.selectLens(pending, lensId).copy(
                exposureApplied = ExposureAppliedState.pending(),
                sensitivityBoost = null,
                rawFps = null,
                viewfinderFps = null,
                faceDetections = emptyList(),
                focus =
                    pending.focus.copy(
                        status = TargetStatus.Hidden,
                        appliedFocusDiopters = null,
                        backendNativeReadout = null
                    )
            )
        }
        coordinator.selectLens(lensId)
        onLensSelected(lensId)
    }

    private fun focusAt(point: NormalizedPoint) {
        if (!state.value.capabilities.tapAfSupported) return
        val p = point.clamped(0f)
        val requestId = focusRequestIds.incrementAndGet()
        update { CaptureTransitions.beginAutofocus(it, p) }
        overlayTriggers.autofocusStart()
        coordinator.focusAt(p.x, p.y, requestId)
    }

    /**
     * Back to full auto (retap on the box / auto-dismiss / Af entry): hides
     * the box and drops the native tap region + trigger so continuous AF
     * meters the full frame again.
     */
    private fun clearAutofocus() {
        val requestId = focusRequestIds.incrementAndGet()
        update(CaptureTransitions::clearAutofocus)
        overlayTriggers.autofocusCancel()
        coordinator.clearFocus(requestId)
    }

    /** Face boxes are ephemeral display data: replace when changed, never accumulate. */
    private fun updateFaces(faces: List<FaceDetection>) {
        if (state.value.faceDetections != faces) {
            update { it.copy(faceDetections = faces) }
        }
    }

    private fun setFocusMode(mode: FocusMode) {
        val state = state.value
        if (mode == FocusMode.Mf && !state.capabilities.manualFocus.supported) return
        if (mode == FocusMode.AfLock && !state.capabilities.tapAfSupported) return
        val requestId = focusRequestIds.incrementAndGet()
        val nativeMode =
            when (mode) {
                FocusMode.Af -> 0
                FocusMode.AfLock -> 1
                FocusMode.Mf -> 2
            }
        val target = state.focus.target.clamped(0f)
        coordinator.setFocusMode(nativeMode, target.x, target.y, requestId)
        if (mode == FocusMode.AfLock) overlayTriggers.autofocusStart()
        update { NativeFocusPresentation.requestMode(it, mode) }
    }

    private fun setManualFocus(normalized: Float) {
        if (!state.value.capabilities.manualFocus.supported) return
        val requestId = focusRequestIds.incrementAndGet()
        update { NativeFocusPresentation.requestManualFocus(it, normalized) }
        coordinator.setManualFocusNormalized(normalized, requestId)
        overlayTriggers.pulsePeakManual()
    }

    private fun enqueueStillCapture(token: Any) {
        val format = CaptureOutputFormat.from(state.value.dngEnabled, state.value.jpegEnabled,
                                              state.value.capabilities.dngOnly)
        // Hold starts at the press; the capture serial hands ownership to native when submission begins.
        if (state.value.fullSizeCapture) update { it.copy(bracketHoldPresses = it.bracketHoldPresses + 1, fullSizePressSerial = it.fullSizeCaptureSerial) }
        capturePresentation.pressed()
        captureQueue.enqueue(format) { foregroundToken() === token }
    }

    private fun captureStill(format: CaptureOutputFormat) {
        val burstFrame = burstWaiting.get() > 0
        val nativeRequestId = try {
            requestStillCapture(format)
        } catch (error: RuntimeException) {
            android.util.Log.e("RawrCamCapture", "Capture preparation failed", error)
            0L
        } finally {
            settleBracketHoldPress()
        }
        if (burstFrame) {
            burstWaiting.decrementAndGet()
            if (nativeRequestId == 0L) {
                // The save queue (memory or storage budget) is full: the run ends here.
                burstRefused.set(true)
            } else {
                update { it.copy(burst = it.burst.copy(shots = it.burst.shots + 1)) }
            }
        }
        if (nativeRequestId == 0L) {
            if (!burstFrame) capturePresentation.rejected(format.jpeg)
            return
        }
        capturePresentation.accepted(nativeRequestId, format)
    }

    // ---- X14U Burst ------------------------------------------------------------------------------------------
    // While the shutter is held, one ordinary still request per frame interval goes to the capture worker; each
    // takes the next camera frame and is developed and saved in the background like any photo. At most two wait for
    // the worker, so a slow moment skips frames instead of shooting them late.
    private var burstJob: Job? = null
    private val burstWaiting = java.util.concurrent.atomic.AtomicInteger(0)
    private val burstRefused = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startBurst(action: StartBurst) {
        if (burstJob?.isActive == true) return
        val token = foregroundToken() ?: return
        burstRefused.set(false)
        val now = java.util.Date()
        // Pixel-style burst id (17 digits): Google Photos stacks frames that share it.
        val tag = "_BURST" + java.text.SimpleDateFormat("yyyyMMddHHmmssSSS", java.util.Locale.US).format(now)
        val folder = "BURST_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(now)
        // Pre-capture: the frames held from before the press freeze now (live frames take the camera from here on);
        // they are numbered first and saved after release.
        val preEstimate = if (action.pre) coordinator.freezePrecapture() else 0
        com.rawr.camera.storage.CaptureFileNames.startBurst(tag, folder, firstLive = preEstimate + 1)
        update { it.copy(burst = it.burst.copy(running = true, shots = 0, fps = action.fps, limit = action.limit,
            stoppedFull = false, startedAtMs = android.os.SystemClock.uptimeMillis(), preFrames = 0)) }
        val interval = (1000L / action.fps.coerceIn(1, 60)).coerceAtLeast(1L)
        // Saving waits until the shutter is let go (it competed with capture and caused the dips, Nov 14).
        coordinator.setStillDevelopPaused(true)
        val startedWall = System.currentTimeMillis()
        burstJob = scope.launch {
            var queued = 0
            var next = android.os.SystemClock.uptimeMillis()
            try {
                while (isActive && queued < action.limit && !burstRefused.get()) {
                    if (burstWaiting.get() < 2) {
                        com.rawr.camera.storage.CaptureFileNames.burstFrameQueued()
                        burstWaiting.incrementAndGet()
                        if (!captureQueue.enqueue(action.format) { foregroundToken() === token }) {
                            burstWaiting.decrementAndGet()
                            com.rawr.camera.storage.CaptureFileNames.burstFrameDropped()
                            break
                        }
                        queued++
                    }
                    next += interval
                    val t = android.os.SystemClock.uptimeMillis()
                    if (next < t) next = t
                    delay(next - t)
                }
            } finally {
                withContext(NonCancellable) {
                    // The frames already queued still count (they are on their way to the worker).
                    while (burstWaiting.get() > 0) delay(20L)
                    val liveMs = android.os.SystemClock.uptimeMillis() - state.value.burst.startedAtMs
                    val live = state.value.burst.shots
                    // Then the frames from before the press, oldest first (numbers 1…): each request takes one.
                    var pre = 0
                    if (action.pre) {
                        val held = coordinator.beginPrecaptureDrain()
                        for (i in 1..held) {
                            com.rawr.camera.storage.CaptureFileNames.burstPreFrameQueued(i)
                            burstWaiting.incrementAndGet()
                            if (!captureQueue.enqueue(action.format) { foregroundToken() === token }) {
                                burstWaiting.decrementAndGet()
                                com.rawr.camera.storage.CaptureFileNames.burstFrameDropped()
                                break
                            }
                            while (burstWaiting.get() >= 3) delay(5L)
                        }
                        while (burstWaiting.get() > 0) delay(20L)
                        pre = state.value.burst.shots - live
                        coordinator.endPrecapture()
                    }
                    coordinator.setStillDevelopPaused(false)
                    android.util.Log.i("RawrCamCapture", "BURST_RUN fps=${action.fps} queued=$queued " +
                        "accepted=$live pre=$pre ms=$liveMs refused=${burstRefused.get()}")
                    onBurstFinished(BurstRun(folder, tag, action.fps, startedWall, liveMs, pre, preEstimate + 1, live,
                        action.burstFormat, action.burstFormat == "Jpeg"))
                    update { it.copy(burst = it.burst.copy(running = false, lastCount = it.burst.shots,
                        stoppedFull = burstRefused.get(), preFrames = pre)) }
                }
            }
        }
    }

    private fun stopBurst() {
        burstJob?.cancel()
    }

    // Native has armed (or skipped) this press's bracket: hand the hold over to
    // its counters in the same update, so the notice never blinks off between.
    private fun settleBracketHoldPress() {
        val snapshot = runCatching { NativeCameraUiSnapshot.parse(coordinator.cameraControlSnapshot()) }.getOrNull()
        update { current ->
            if (current.bracketHoldPresses == 0) current
            else current.copy(
                bracketHoldPresses = current.bracketHoldPresses - 1,
                bracketHoldOpened = snapshot?.bracketHoldOpened ?: current.bracketHoldOpened,
                bracketHoldDone = snapshot?.bracketHoldDone ?: current.bracketHoldDone,
                fullSizeCapturePending = snapshot?.fullSizeCapturePending ?: current.fullSizeCapturePending
            )
        }
    }

    private fun applyNativeSnapshot(snapshot: NativeCameraUiSnapshot) {
        // Capture progress, independent of lens/mode reconciliation below. The
        // counters only grow; a snapshot read before settleBracketHoldPress must
        // not roll them back.
        update { current ->
            val opened = maxOf(current.bracketHoldOpened, snapshot.bracketHoldOpened)
            val done = maxOf(current.bracketHoldDone, snapshot.bracketHoldDone)
            current.copy(
                bracketHoldOpened = opened,
                bracketHoldDone = done,
                fullSizeCaptureSerial = snapshot.fullSizeCaptureSerial,
                fullSizeExposurePending = snapshot.fullSizeExposurePending,
                restoringPreview = snapshot.restoringPreview,
                companionViewfinder = snapshot.companionViewfinder,
                fullSizeCapture = snapshot.fullSizeCapture,
                fullSizeCapturePending = snapshot.fullSizeCapturePending,
                fullSizeBinnedStills = maxOf(current.fullSizeBinnedStills, snapshot.fullSizeBinnedStills),
                fullSizeBurstsToSingle = maxOf(current.fullSizeBurstsToSingle, snapshot.fullSizeBurstsToSingle)
            )
        }
        val prepared = snapshotPresentation.prepare(state.value, snapshot) ?: return
        update { current ->
            prepared.project(current, focusRequestIds.get(), whiteBalanceRequestIds.get())
        }
        // X14U: 60 fps only where the lens's RAW stream runs at 60; another lens drops to 30.
        val projected = state.value
        if (prepared.contextChanged && projected.captureMode == CaptureMode.Video && projected.videoFps == 60 &&
            !projected.capabilities.videoSixtyFps) {
            update { it.copy(videoFps = 30) }
            coordinator.setVideoMode(true, 30)
            onCapturePreferencesChanged(projected, state.value)
        }
        selfTimer.reconcile()
    }

    private inline fun update(transform: (CaptureUiState) -> CaptureUiState) {
        while (true) {
            val current = mutableState.value
            val next = transform(current)
            if (next === current || mutableState.compareAndSet(current, next)) return
        }
    }

    override fun close() {
        selfTimer.close()
        capturePresentation.close()
        scope.cancel()
    }
}

/** X14U Burst: what a run was, for its manifest (the burst viewer reads it). */
data class BurstRun(
    val folder: String,
    val tag: String,
    val fps: Int,
    val startedAtMillis: Long,
    val liveMs: Long,
    val preFrames: Int,
    /** File number of the press frame (the cover). */
    val pressNumber: Int,
    val liveFrames: Int,
    val format: String,
    val rawKept: Boolean
)
