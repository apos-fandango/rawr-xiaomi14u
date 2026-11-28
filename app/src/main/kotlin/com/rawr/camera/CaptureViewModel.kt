package com.rawr.camera

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SyncToneControls
import com.rawr.camera.integration.DeviceLensDefaults
import com.rawr.camera.settings.model.effectiveLensProfiles
import com.rawr.camera.settings.model.selectOption
import com.rawr.camera.settings.model.withRunAsRowsOff
import com.rawr.camera.settings.model.runAsTarget
import com.rawr.camera.settings.model.runsAsCompanion
import com.rawr.camera.integration.NativeCaptureScreenController
import com.rawr.camera.integration.RawPreviewCoordinator
import com.rawr.camera.integration.StillCaptureCoordinator
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.ToneParameter
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.toFilmSimQuickState
import com.rawr.camera.model.toRenderProfileQuickState
import com.rawr.camera.model.toViewfinderQuickState
import com.rawr.camera.model.withToneParameter
import com.rawr.camera.settings.architecture.SetFilmSimDiscreteValue
import com.rawr.camera.settings.architecture.SetFilmSimFlag
import com.rawr.camera.settings.architecture.SetFilmSimNumericValue
import com.rawr.camera.settings.architecture.capturePreferences
import com.rawr.camera.settings.architecture.editActiveProfileTone
import com.rawr.camera.settings.architecture.withAgxDefault
import com.rawr.camera.settings.architecture.withAgxValue
import com.rawr.camera.settings.architecture.withCapturePreferenceChanges
import com.rawr.camera.settings.architecture.withFilmDiscrete
import com.rawr.camera.settings.architecture.withFilmFlag
import com.rawr.camera.settings.architecture.withFilmNumeric
import com.rawr.camera.settings.architecture.withFilmPreset
import com.rawr.camera.settings.model.FilmSimSpecs
import com.rawr.camera.settings.model.SettingsCatalog
import com.rawr.camera.settings.model.withRenderProfile
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.isFilmPresetModified
import com.rawr.camera.settings.model.isUserFilmPreset
import com.rawr.camera.settings.model.orderedFilmPresets
import com.rawr.camera.settings.model.withFilmPresetDeleted
import com.rawr.camera.settings.model.withFilmPresetMoved
import com.rawr.camera.settings.model.withFilmPresetRenamed
import com.rawr.camera.settings.model.withFilmPresetReverted
import com.rawr.camera.settings.model.withFilmPresetSaved
import com.rawr.camera.settings.model.withFilmPresetUpdated
import com.rawr.camera.settings.model.editThroughLens
import com.rawr.camera.settings.model.withLensOverrides
import com.rawr.camera.settings.model.options
import com.rawr.camera.settings.model.persistedId
import com.rawr.camera.settings.model.storedValue
import com.rawr.camera.settings.preferences.SettingsPreferencesStore
import com.rawr.camera.storage.GalleryMediaStore
import com.rawr.camera.video.VideoResolutionMode
import com.rawr.camera.video.toVideoImageSettings
import com.rawr.camera.video.videoProfileAllows
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rawr.camera.settings.model.withModeLookSwitch
import com.rawr.camera.settings.model.withBaseline
import com.rawr.camera.settings.model.withPresetGroups
import com.rawr.camera.settings.model.presetSummary
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.ui.PresetActions
import com.rawr.camera.ui.PresetItem
import com.rawr.camera.ui.PresetsUi

/**
 * Application lifetime owner.
 *
 * Compose state is production-backed for camera/lens/exposure/focus controls.
 * Processing controls not yet integrated remain application/UI concerns.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val previewCoordinator = RawPreviewCoordinator(application)
    private val settingsDefaults = SettingsCatalog.initialState().values
    private val settingsEditor = SettingsPreferencesStore.editor(application, settingsDefaults)

    /** X14U: what the camera and captures use: global settings with the active lens's overrides applied. */
    private val latestSettings: SettingsValues get() = settingsEditor.state.value.withLensOverrides(runAs = shotRunsAsLens)

    /** Pano and Burst shoot 12 MP frames: a lens's "50 mp" choice (its run-as lens's overrides) doesn't apply there. */
    private val shotRunsAsLens: Boolean
        // runCatching: the flows are declared further down (a read during construction keeps the default).
        get() = runCatching { !panoFlow.value && !burstFlow.value }.getOrDefault(true)
    private val effectiveValues = settingsEditor.values.map { it.withLensOverrides() }

    /** A capture-screen edit: settings the active lens overrides change on the lens ([perLens] keys always). */
    private fun updateForLens(perLens: Set<String> = emptySet(), edit: (SettingsValues) -> SettingsValues): SettingsValues =
        settingsEditor.update { it.editThroughLens(perLens, DeviceLensDefaults.lenses, edit) }
    val cameraLocation = com.rawr.camera.location.CameraLocation.get(application)
    private val levelSensor = com.rawr.camera.integration.CaptureLevelSensor(application)
    private val thermalLog = com.rawr.camera.integration.ThermalLog(application)
    val foreground = com.rawr.camera.integration.CaptureForeground { active ->
        cameraLocation.setForeground(active)
        levelSensor.setActive(active)
        thermalLog.setActive(active)
    }
    fun videoImageSettings(): com.rawr.camera.video.VideoImageSettings = latestSettings.toVideoImageSettings()
    fun videoEncoderConfig(): com.rawr.camera.settings.model.VideoEncoderConfig = latestSettings.videoEncoder.sanitized()
    /** Microphone setup for the video-mode meter; follows Settings edits. */
    val videoAudioInput: StateFlow<com.rawr.camera.video.VideoAudioInput> = settingsEditor.state
        .map { com.rawr.camera.video.VideoAudioInput.of(it.videoEncoder.sanitized()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.rawr.camera.video.VideoAudioInput.of(latestSettings.videoEncoder.sanitized()))
    /** X14U: Settings → Video → Show diagnostics. */
    val videoDiagnostics: StateFlow<Boolean> = settingsEditor.state
        .map { it.videoEncoder.showDiagnostics }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, latestSettings.videoEncoder.showDiagnostics)

    /** X14U: Pro (new screen) or Photo (classic screen) for photos. */
    val proUi: StateFlow<Boolean> = settingsEditor.state
        .map { it.x14uProUi }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, latestSettings.x14uProUi)

    /** X14U Pano mode (UI only; stills run as Photo). Not kept across restarts. */
    private val panoFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pano: StateFlow<Boolean> = panoFlow
    fun setPano(on: Boolean) {
        panoFlow.value = on
        if (!on) com.rawr.camera.storage.CaptureFileNames.setNextStillSuffix("")
    }

    /** X14U Burst mode (stills run as Photo, one frame each, no merge). Not kept across restarts, like Pano. */
    private val burstFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val burst: StateFlow<Boolean> = burstFlow
    fun setBurst(on: Boolean) {
        if (burstFlow.value == on) return
        burstFlow.value = on
        if (!on) {
            previewCoordinator.armPrecapture(0, 50)
            controller.dispatch(com.rawr.camera.architecture.EndBurstMode)
        } else {
            // Without this the first ~6 frames waited 25-110 ms each for a RAW buffer (diagnostics 14 Nov).
            previewCoordinator.prewarmStillBuffers(6)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.rawr.camera.burst.BurstStore.cleanup(getApplication())
                refreshLatestBurst()
            }
        }
    }

    /** Burst pre-capture: the ring holds [seconds] at [fps] (0 = off); the capture screen re-arms it after each run. */
    fun armPrecapture(seconds: Int, fps: Int) {
        val frames = if (burstFlow.value) seconds * fps else 0
        previewCoordinator.armPrecapture(frames, 1000 / fps.coerceIn(1, 60))
    }

    fun precaptureHeld(): Int = previewCoordinator.precaptureHeld()

    /** A burst run's manifest (files/bursts/<folder>.json): times, press frame, speed, pre-capture count. */
    private fun writeBurstManifest(run: com.rawr.camera.integration.BurstRun) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(getApplication<Application>().filesDir, "bursts").apply { mkdirs() }
                val json = org.json.JSONObject()
                    .put("folder", run.folder).put("tag", run.tag).put("fps", run.fps)
                    .put("startedAt", run.startedAtMillis).put("liveMs", run.liveMs)
                    .put("preFrames", run.preFrames).put("pressNumber", run.pressNumber)
                    .put("liveFrames", run.liveFrames).put("format", run.format)
                    .put("rawKept", run.rawKept).put("cover", run.pressNumber)
                    .put("rawKeepDays", if (run.format == "Jpeg") com.rawr.camera.burst.BurstSettings.RAW_KEEP_DAYS else 0)
                java.io.File(dir, run.folder + ".json").writeText(json.toString(2))
            }
            refreshLatestBurst()
        }
    }

    /** Burst mode: the gallery thumbnail shows the last burst's cover and opens the burst viewer. */
    private val latestBurstFlow = kotlinx.coroutines.flow.MutableStateFlow<Uri?>(null)
    val latestBurst: StateFlow<Uri?> = latestBurstFlow
    @Volatile var latestBurstFolder: String? = null
        private set

    private fun refreshLatestBurst() {
        val latest = com.rawr.camera.burst.BurstStore.latest(getApplication())
        latestBurstFolder = latest?.folder
        latestBurstFlow.value = latest?.coverFrame?.view?.let(Uri::fromFile)
    }

    /** Private burst RAW older than the keep time goes (files/burst_raw/<folder>/). */
    private fun cleanBurstRaw() {
        val root = java.io.File(getApplication<Application>().filesDir, "burst_raw")
        val limit = System.currentTimeMillis() - com.rawr.camera.burst.BurstSettings.RAW_KEEP_DAYS * 86_400_000L
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < limit }?.forEach { it.deleteRecursively() }
    }

    /** X14U Pano merge on the phone: progress and result for the viewfinder (clears a few seconds after the end). */
    private val panoStatusFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val panoStatus: StateFlow<String?> = panoStatusFlow
    private val panoMerger by lazy { com.rawr.camera.pano.PanoMerger(getApplication()) }
    private var panoStatusClear: kotlinx.coroutines.Job? = null

    fun mergePano(tag: String, frames: Int, startedAtMillis: Long, spanDegrees: Float, vertical: Boolean) {
        val settings = latestSettings
        if (!settings.panoMerge || frames < 2 || tag.isEmpty()) return
        viewModelScope.launch {
            panoMerger.merge(tag, frames, startedAtMillis, spanDegrees, settings.panoProjection, settings.panoKeepFrames, vertical) { message ->
                panoStatusClear?.cancel()
                panoStatusFlow.value = message
                // The thumbnail follows the new panorama (and leaves frames that "keep frames" off deletes).
                if (message?.startsWith("Pano saved") == true) refreshLatestGalleryImage()
                // Progress stays; a result clears after a few seconds.
                if (message != null && !message.endsWith("…")) {
                    panoStatusClear = viewModelScope.launch {
                        kotlinx.coroutines.delay(5_000L)
                        panoStatusFlow.value = null
                    }
                }
            }
            // Frame deletion runs on after "saved" (late DNGs): point the thumbnail at what's left.
            if (!settings.panoKeepFrames) refreshLatestGalleryImage()
        }
    }

    /**
     * X14U 50 MP shot: a lens whose chosen option runs as another lens (1x "50 mp" → 50MP) keeps its 12 MP viewfinder,
     * where AF and MF work; the shutter switches to the run-as lens, takes the shot and switches back. The full-size
     * mode doesn't move the focus motor, so the lens keeps the focus the viewfinder found.
     */
    // Lazy: the controller is created further down.
    val fullSizeShot: StateFlow<Boolean> by lazy { kotlinx.coroutines.flow.combine(
        settingsEditor.values.map { it.effectiveLensProfiles(DeviceLensDefaults.lenses) },
        controller.state.map { Triple(it.selectedLensId, it.captureMode == com.rawr.camera.model.CaptureMode.Video, Unit) },
        panoFlow,
        burstFlow
    ) { lenses, (lensId, video, _), pano, burst ->
        // Burst runs 12 MP frames (no 50 MP shot there). A camera-processed-viewfinder run-as lens isn't a per-shot
        // switch: it runs all the time while chosen (companionRunAs).
        val lens = lenses.firstOrNull { it.name.trim().equals(lensId.trim(), ignoreCase = true) }
        !video && !pano && !burst && lens?.runAsTarget() != null && !lens.runsAsCompanion(lenses)
    }.distinctUntilChanged().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false) }

    /**
     * X14U 3x 50 MP (stage 3): a lens whose chosen option runs as a lens with the camera-processed viewfinder
     * (session.companionViewfinder) runs as it for as long as it's chosen, in Photo / Pro: that lens has its own live
     * viewfinder with AF, and the shutter is a normal shot. Pano, Burst and Video run the lens as itself.
     */
    private val companionRunAs: kotlinx.coroutines.flow.Flow<Boolean> by lazy { kotlinx.coroutines.flow.combine(
        settingsEditor.values.map { it.effectiveLensProfiles(DeviceLensDefaults.lenses) },
        controller.state.map { it.selectedLensId to (it.captureMode == com.rawr.camera.model.CaptureMode.Video) },
        panoFlow,
        burstFlow
    ) { lenses, (lensId, video), pano, burst ->
        !video && !pano && !burst &&
            lenses.firstOrNull { it.name.trim().equals(lensId.trim(), ignoreCase = true) }?.runsAsCompanion(lenses) == true
    }.distinctUntilChanged() }

    private var fullSizeJob: kotlinx.coroutines.Job? = null

    /** A 50 MP shot is running: the capture screen locks the lens row and controls until it's back. */
    private val fullSizeBusyFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val fullSizeBusy: StateFlow<Boolean> = fullSizeBusyFlow

    /**
     * The shutter press, when the selected lens's chosen option runs as another lens right now (read at the press,
     * not from a flow that may lag the toggle): takes the 50 MP shot and returns true; false = a normal shot.
     */
    fun tryShootFullSize(): Boolean {
        val state = controller.state.value
        if (state.captureMode == com.rawr.camera.model.CaptureMode.Video || panoFlow.value || burstFlow.value) return false
        val lens = latestSettings.effectiveLensProfiles(DeviceLensDefaults.lenses)
            .firstOrNull { it.name.trim().equals(state.selectedLensId.trim(), ignoreCase = true) } ?: return false
        if (lens.runAsTarget() == null) return false
        // Already running as its camera-processed-viewfinder lens: a normal shot.
        if (lens.runsAsCompanion(latestSettings.effectiveLensProfiles(DeviceLensDefaults.lenses))) return false
        shootFullSize()
        return true
    }

    /** The 50 MP shot (see [fullSizeShot]); a press while one runs is ignored. */
    fun shootFullSize() {
        if (fullSizeJob?.isActive == true) return
        fullSizeBusyFlow.value = true
        fullSizeJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            fun status(message: String?) {
                panoStatusClear?.cancel()
                panoStatusFlow.value = message
                // The thumbnail follows the new panorama (and leaves frames that "keep frames" off deletes).
                if (message?.startsWith("Pano saved") == true) refreshLatestGalleryImage()
            }
            suspend fun waitFor(timeoutMs: Long, ready: () -> Boolean) = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                while (!ready()) kotlinx.coroutines.delay(30L)
            } != null
            try {
                status("50 MP…")
                // The viewfinder holds its last 12 MP frame, and the shot keeps the 12 MP view's white balance (a new
                // session would start its own auto white balance cold: the ultra-wide's especially, at 12 fps).
                previewCoordinator.setViewfinderHold(true)
                previewCoordinator.setCarryWhiteBalance(true)
                previewCoordinator.setRunAsForShot(true)
                // Shoot only on the full-size session's own frames (never a 12 MP session on the way); a few of them
                // for exposure to settle.
                if (!waitFor(8_000L) { previewCoordinator.heldNewFullSizeFrames() >= 3 }) {
                    status("50 MP didn't start")
                    kotlinx.coroutines.delay(2_500L)
                    return@launch
                }
                kotlinx.coroutines.delay(300L)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    controller.dispatch(com.rawr.camera.architecture.TriggerCapture)
                }
                // The press holds the camera from when native takes it (after any self-timer) until its frames are in.
                waitFor(12_000L) { previewCoordinator.stillCaptureHoldsCamera() }
                waitFor(25_000L) { !previewCoordinator.stillCaptureHoldsCamera() }
            } finally {
                // Always back to 12 MP, even if the job was cancelled: held (on the carried white balance) until the
                // 12 MP session's own frames are in, then live again.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    previewCoordinator.setViewfinderHold(true)
                    previewCoordinator.setRunAsForShot(false)
                    waitFor(4_000L) {
                        previewCoordinator.heldNewFrames() - previewCoordinator.heldNewFullSizeFrames() >= 2
                    }
                    previewCoordinator.setCarryWhiteBalance(false)
                    previewCoordinator.setViewfinderHold(false)
                    status(null)
                    fullSizeBusyFlow.value = false
                }
            }
        }
    }

    /**
     * Pano undo: removes the files of the frame tagged [tag] ("_PANO2231_03") that this app saved. A merge may still
     * be writing them, so it looks again a few times over the next two minutes.
     */
    fun deletePanoFrame(tag: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (wait in longArrayOf(0L, 4_000L, 15_000L, 45_000L, 60_000L)) {
                kotlinx.coroutines.delay(wait)
                val removed = runCatching {
                    app.contentResolver.delete(
                        android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL),
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                            "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                        arrayOf("%$tag%", app.packageName)
                    )
                }.getOrDefault(0)
                if (removed > 0) refreshLatestGalleryImage()
            }
        }
    }

    fun setProUi(pro: Boolean) {
        settingsEditor.update { prev ->
            prev.copy(x14uProUi = pro, stillsSwitched = prev.stillsSwitched || pro != prev.x14uProUi).withModeLookSwitch(prev)
        }
    }

    /** X14U tools panel: what its tiles show (lens names whose image is mirrored, for the front camera's tile). */
    data class ToolsState(
        val photoAspect: com.rawr.camera.settings.model.PhotoAspect = com.rawr.camera.settings.model.PhotoAspect.Native,
        val level: Boolean = false,
        val location: Boolean = false,
        val mirroredLenses: Set<String> = emptySet(),
        val focusLoupe: com.rawr.camera.settings.model.FocusLoupeSettings = com.rawr.camera.settings.model.FocusLoupeSettings(),
        /** Digital zoom chain: lens name to its zoom factor, widest first. */
        val zoomChain: List<Pair<String, Float>> = emptyList(),
        /** Video horizon lock (the Level tile's Lock). */
        val horizonLock: Boolean = false,
        /** Video horizon Follow (the Level tile's Follow). */
        val horizonFollow: Boolean = false,
        /** Video stabilisation (the Stabilise tile). */
        val stabilize: com.rawr.camera.settings.model.VideoStabilize = com.rawr.camera.settings.model.VideoStabilize.Off,
        /** The lens pill: labels and per-mode sets with digital focal lengths. */
        val lensButtons: com.rawr.camera.settings.model.LensButtons = com.rawr.camera.settings.model.LensButtons(),
        /** Video: the small sound meter above the histogram. */
        val audioMeter: Boolean = true,
        /** Photography Kit grip read over USB, and what its dial turns. */
        val gripUsb: Boolean = true,
        val gripDial: com.rawr.camera.settings.model.GripDial = com.rawr.camera.settings.model.GripDial.Ev,
        val gripDialReverse: Boolean = false,
        /** Pano: how the merge projects the set (the Projection tile in Pano). */
        val panoProjection: com.rawr.camera.settings.model.PanoProjection = com.rawr.camera.settings.model.PanoProjection.Auto
    )
    val tools: StateFlow<ToolsState> = settingsEditor.state
        .map { v ->
            ToolsState(
                v.photoAspect, v.viewfinderLevel, v.locationTagging,
                v.effectiveLensProfiles(DeviceLensDefaults.lenses)
                    .filter { it.image.mirror != com.rawr.camera.settings.model.LensMirror.None }.map { it.name }.toSet(),
                v.focusLoupe,
                com.rawr.camera.settings.model.ZoomChain.of(v.effectiveLensProfiles(DeviceLensDefaults.lenses)),
                v.videoHorizonLock,
                v.videoHorizonFollow,
                v.videoStabilize,
                v.lensButtons,
                v.videoAudioMeter,
                v.gripUsb,
                v.gripDial,
                v.gripDialReverse,
                v.panoProjection
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ToolsState())

    /** X14U digital zoom: crop on top of the current lens (1 = none); photos only. */
    @Volatile private var digitalZoomCrop = 1f
    fun setDigitalZoom(crop: Float) {
        val c = crop.coerceIn(1f, 8f)
        if (c == digitalZoomCrop) return
        digitalZoomCrop = c
        previewCoordinator.setDigitalZoom(c, latestSettings.zoomUpscale)
    }

    fun setPhotoAspect(aspect: com.rawr.camera.settings.model.PhotoAspect) {
        settingsEditor.update { it.copy(photoAspect = aspect) }
    }

    fun setPanoProjection(projection: com.rawr.camera.settings.model.PanoProjection) {
        settingsEditor.update { it.copy(panoProjection = projection) }
    }

    // X14U presets (Nov 17): P0 is the baseline (made from the settings in use the first time), P1… follow.
    private val presetCodec by lazy {
        com.rawr.camera.settings.preferences.PresetSettingsCodec(getApplication(), settingsDefaults)
    }
    /** Decoded preset settings by their saved text (decoding runs through the settings migrations). */
    private val presetSnaps = object : LinkedHashMap<String, SettingsValues>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SettingsValues>?) = size > 24
    }

    private fun presetSnap(preset: com.rawr.camera.settings.model.CapturePreset, current: SettingsValues): SettingsValues? =
        synchronized(presetSnaps) { presetSnaps[preset.settings] }
            ?: presetCodec.decode(preset.settings, current)?.also { synchronized(presetSnaps) { presetSnaps[preset.settings] = it } }

    private fun newBaseline(v: SettingsValues) = com.rawr.camera.settings.model.CapturePreset(
        id = com.rawr.camera.settings.model.CapturePreset.BASELINE_ID,
        name = "Default",
        groups = com.rawr.camera.settings.model.PresetGroup.entries.toSet(),
        settings = presetCodec.encode(v)
    )

    private fun SettingsValues.presetList() = capturePresets.withBaseline { newBaseline(this) }

    val presets: StateFlow<PresetsUi> = settingsEditor.state
        .map { v ->
            val list = v.presetList()
            val active = list.firstOrNull { it.id == v.activePresetId } ?: list.first()
            // Compared as stored, so an in-memory difference that doesn't survive saving (a rounding) isn't a change.
            // X14U (Nov 26): film is optional in a preset and has its own strip, so a film change doesn't mark it.
            val changed = presetSnap(active, v)?.let { snap ->
                val merged = v.withPresetGroups(snap, active.groups - com.rawr.camera.settings.model.PresetGroup.Film)
                merged != v && presetCodec.encode(merged) != presetCodec.encode(v)
            } ?: false
            PresetsUi(
                items = list.mapIndexed { i, p ->
                    PresetItem(
                        id = p.id, label = "P$i", name = p.name, groups = p.groups, camera = p.camera,
                        summary = presetSnap(p, v)?.let { snap ->
                            p.groups.associateWith { snap.presetSummary(it) }.filterValues { it.isNotEmpty() }
                        }.orEmpty()
                    )
                },
                activeId = active.id,
                settingsChanged = changed,
                current = com.rawr.camera.settings.model.PresetGroup.entries
                    .associateWith { v.presetSummary(it) }.filterValues { it.isNotEmpty() }
            )
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, PresetsUi())

    /** X14U (Nov 26): film recipes for the FILM plate and strip (factory and saved, in the strip's order). */
    val filmRecipes: StateFlow<com.rawr.camera.ui.FilmRecipesUi> = settingsEditor.state
        .map { v ->
            com.rawr.camera.ui.FilmRecipesUi(
                items = com.rawr.camera.ui.filmRecipeItems(v.orderedFilmPresets()) { v.isUserFilmPreset(it) },
                activeId = v.selectedFilmPresetId,
                modified = v.isFilmPresetModified()
            )
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.rawr.camera.ui.FilmRecipesUi())

    val filmRecipeActions: com.rawr.camera.ui.FilmRecipeActions = object : com.rawr.camera.ui.FilmRecipeActions {
        private fun newId(v: SettingsValues) =
            com.rawr.camera.settings.architecture.newPresetId(v.filmPresets.map { it.id })

        override fun apply(id: String) { settingsEditor.update { it.withFilmPreset(id) } }
        override fun save(name: String) { settingsEditor.update { v -> v.withFilmPresetSaved(name, newId(v)) } }
        override fun duplicate(id: String) {
            settingsEditor.update { v ->
                val p = v.orderedFilmPresets().firstOrNull { it.id == id } ?: return@update v
                v.withFilmPresetSaved("${p.name} copy", newId(v), p.look, select = false)
            }
        }
        override fun update(id: String) { settingsEditor.update { it.withFilmPresetUpdated(id) } }
        override fun revert() { settingsEditor.update { it.withFilmPresetReverted() } }
        override fun rename(id: String, name: String) { settingsEditor.update { it.withFilmPresetRenamed(id, name) } }
        override fun delete(id: String) { settingsEditor.update { it.withFilmPresetDeleted(id) } }
        override fun move(from: Int, to: Int) { settingsEditor.update { it.withFilmPresetMoved(from, to) } }

        /** Film look and recipe from before the editor opened. */
        private var beforeEdit: Pair<com.rawr.camera.settings.model.FilmSimLook, String?>? = null

        override fun beginEdit(id: String?) {
            val v = settingsEditor.state.value
            beforeEdit = v.filmSimLook to v.selectedFilmPresetId
            if (id != null) settingsEditor.update { it.withFilmPreset(id) }
        }
        override fun cancelEdit() {
            val (look, selected) = beforeEdit ?: return
            beforeEdit = null
            settingsEditor.update { it.copy(filmSimLook = look, selectedFilmPresetId = selected) }
        }
        override fun finishEdit() { beforeEdit = null }
    }

    private fun editPresets(edit: (SettingsValues, MutableList<com.rawr.camera.settings.model.CapturePreset>) -> String?) {
        settingsEditor.update { v ->
            val list = v.presetList().toMutableList()
            val active = edit(v, list) ?: v.activePresetId
            v.copy(capturePresets = list, activePresetId = active.takeIf { id -> list.any { it.id == id } }
                ?: com.rawr.camera.settings.model.CapturePreset.BASELINE_ID)
        }
    }

    /**
     * [v] saved for a preset, and [v] as that saved form reads back: the live settings take the read-back values of
     * the preset's groups, so a just-saved preset never shows as changed over a rounding.
     */
    private fun encodeForPreset(v: SettingsValues, groups: Set<com.rawr.camera.settings.model.PresetGroup>): Pair<String, SettingsValues> {
        val saved = presetCodec.encode(v)
        val back = presetCodec.decode(saved, v)?.let { v.withPresetGroups(it, groups) } ?: v
        return saved to back
    }

    val presetActions: PresetActions = object : PresetActions {
        override fun apply(id: String) {
            settingsEditor.update { v ->
                val preset = v.presetList().firstOrNull { it.id == id } ?: return@update v
                val snap = presetSnap(preset, v) ?: return@update v
                v.withPresetGroups(snap, preset.groups).copy(capturePresets = v.presetList(), activePresetId = id)
            }
        }

        override fun save(name: String, groups: Set<com.rawr.camera.settings.model.PresetGroup>,
            camera: com.rawr.camera.settings.model.PresetCamera) {
            if (groups.isEmpty()) return
            settingsEditor.update { v ->
                val list = v.presetList()
                if (list.size >= com.rawr.camera.settings.model.CapturePreset.MAX_PRESETS) return@update v
                val id = "p" + java.util.UUID.randomUUID().toString().take(8)
                val (saved, back) = encodeForPreset(v, groups)
                back.copy(
                    capturePresets = list + com.rawr.camera.settings.model.CapturePreset(
                        id, name.trim().take(com.rawr.camera.settings.model.CapturePreset.MAX_NAME).ifEmpty { "P${list.size}" },
                        groups, saved, camera.only(groups)
                    ),
                    activePresetId = id
                )
            }
        }

        override fun update(id: String, camera: com.rawr.camera.settings.model.PresetCamera) {
            settingsEditor.update { v ->
                val list = v.presetList().toMutableList()
                val i = list.indexOfFirst { it.id == id }
                if (i < 0) return@update v
                val (saved, back) = encodeForPreset(v, list[i].groups)
                list[i] = list[i].copy(settings = saved, camera = camera.only(list[i].groups))
                back.copy(capturePresets = list, activePresetId = id)
            }
        }

        override fun rename(id: String, name: String) = editPresets { _, list ->
            val i = list.indexOfFirst { it.id == id }
            val trimmed = name.trim().take(com.rawr.camera.settings.model.CapturePreset.MAX_NAME)
            if (i >= 0 && trimmed.isNotEmpty()) list[i] = list[i].copy(name = trimmed)
            null
        }

        override fun removeGroup(id: String, group: com.rawr.camera.settings.model.PresetGroup) = editPresets { _, list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0 && !list[i].isBaseline && group in list[i].groups && list[i].groups.size > 1) {
                val groups = list[i].groups - group
                list[i] = list[i].copy(groups = groups, camera = list[i].camera.only(groups))
            }
            null
        }

        override fun duplicate(id: String) = editPresets { _, list ->
            val i = list.indexOfFirst { it.id == id }
            if (i < 0 || list.size >= com.rawr.camera.settings.model.CapturePreset.MAX_PRESETS) return@editPresets null
            val copy = list[i].copy(
                id = "p" + java.util.UUID.randomUUID().toString().take(8),
                name = (list[i].name.take(com.rawr.camera.settings.model.CapturePreset.MAX_NAME - 5) + " copy")
            )
            list.add(i + 1, copy)
            null
        }

        override fun delete(id: String) = editPresets { v, list ->
            if (id == com.rawr.camera.settings.model.CapturePreset.BASELINE_ID) return@editPresets null
            list.removeAll { it.id == id }
            if (v.activePresetId == id) com.rawr.camera.settings.model.CapturePreset.BASELINE_ID else null
        }

        override fun move(from: Int, to: Int) = editPresets { _, list ->
            if (from in 1 until list.size && to in 1 until list.size && from != to) list.add(to, list.removeAt(from))
            null
        }

        override fun restoreBaseline() = editPresets { _, list ->
            list[0] = list[0].copy(
                settings = presetCodec.encode(settingsDefaults),
                groups = com.rawr.camera.settings.model.PresetGroup.entries.toSet(),
                camera = com.rawr.camera.settings.model.PresetCamera(
                    exposure = com.rawr.camera.settings.model.PresetExposure(ExposureMode.Auto.name, "", "", ""),
                    whiteBalance = com.rawr.camera.settings.model.PresetWhiteBalance(
                        com.rawr.camera.model.WhiteBalanceMode.Auto.name, com.rawr.camera.model.WhiteBalanceMode.TEMP_DEFAULT_K, 0)
                )
            )
            null
        }

        override fun resetToBaseline(groups: Set<com.rawr.camera.settings.model.PresetGroup>) {
            settingsEditor.update { v ->
                val baseline = v.presetList().first()
                val snap = presetSnap(baseline, v) ?: return@update v
                v.withPresetGroups(snap, groups intersect baseline.groups).copy(capturePresets = v.presetList())
            }
        }
    }

    private fun com.rawr.camera.settings.model.PresetCamera.only(groups: Set<com.rawr.camera.settings.model.PresetGroup>) = copy(
        exposure = exposure.takeIf { com.rawr.camera.settings.model.PresetGroup.Exposure in groups },
        whiteBalance = whiteBalance.takeIf { com.rawr.camera.settings.model.PresetGroup.WhiteBalance in groups },
        lens = lens.takeIf { com.rawr.camera.settings.model.PresetGroup.Lens in groups }
    )
    /** One-time "Swipe up for Photo+" hint: shows until Photo/Photo+ is switched once, at most twice. */
    val stillsHint: StateFlow<Boolean> = settingsEditor.state
        .map { !it.stillsSwitched && it.stillsHintCount < 2 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    fun stillsHintShown() {
        settingsEditor.update { it.copy(stillsHintCount = it.stillsHintCount + 1) }
    }

    /** The Level tile in Video: Lock and Follow are exclusive, and either shows the level too. */
    fun setHorizonMode(lock: Boolean, follow: Boolean) {
        settingsEditor.update {
            it.copy(
                videoHorizonLock = lock,
                videoHorizonFollow = follow && !lock,
                viewfinderLevel = it.viewfinderLevel || lock || follow
            )
        }
    }

    fun toggleLevel() {
        // Level off is lock / follow off too (the tile's Off).
        settingsEditor.update {
            it.copy(
                viewfinderLevel = !it.viewfinderLevel,
                videoHorizonLock = it.videoHorizonLock && !it.viewfinderLevel,
                videoHorizonFollow = it.videoHorizonFollow && !it.viewfinderLevel
            )
        }
    }

    fun setStabilize(mode: com.rawr.camera.settings.model.VideoStabilize) {
        settingsEditor.update { it.copy(videoStabilize = mode) }
    }

    /** X14U video horizon lock: gravity in, roll and crop out to the native viewfinder and recorder. */
    private val horizonSensor = com.rawr.camera.integration.HorizonLockSensor(application) { roll, crop, natural, viewRoll, fit ->
        stabilizerSensor.horizonRoll = roll
        previewCoordinator.setHorizonLock(roll, crop, natural, viewRoll, fit)
    }

    /** X14U video stabilisation: gyro in, the shake track out to the native recorder. */
    private val stabilizerSensor = com.rawr.camera.integration.StabilizerSensor(application) { t, pitch, yaw, roll ->
        previewCoordinator.pushStabilizerSample(t, pitch, yaw, roll)
    }
    fun setLocationTagging(on: Boolean) {
        settingsEditor.update { it.copy(locationTagging = on) }
    }

    /**
     * Mirror front: flips the lens image left-right as seen (viewfinder, photo, video; DNG by orientation). The lens
     * mirror is in the sensor frame, so a sensor mounted at 90° / 270° mirrors on its vertical axis.
     */
    fun toggleLensMirror(lensId: String) {
        settingsEditor.update { v ->
            v.copy(lensProfiles = v.effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                if (lens.name != lensId) lens else {
                    val next = if (lens.image.mirror != com.rawr.camera.settings.model.LensMirror.None) {
                        com.rawr.camera.settings.model.LensMirror.None
                    } else {
                        val id = lens.camera.physical.ifEmpty { lens.camera.id }
                        val sensor = runCatching {
                            getApplication<Application>().getSystemService(android.hardware.camera2.CameraManager::class.java)
                                .getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        }.getOrNull() ?: 270
                        if (sensor % 180 == 90) com.rawr.camera.settings.model.LensMirror.Vertical
                        else com.rawr.camera.settings.model.LensMirror.Horizontal
                    }
                    lens.copy(image = lens.image.copy(mirror = next))
                }
            })
        }
    }

    /** X14U: the AUDIO button (tap: mute / unmute). */
    fun toggleAudioMuted() {
        settingsEditor.update { it.copy(videoEncoder = it.videoEncoder.copy(audioMuted = !it.videoEncoder.audioMuted)) }
    }

    /** Startup diagnostics use the same optimistic owner, including when preferences are still loading. */
    fun applyStartupVideoOverrides(resolution: VideoResolutionMode?, fps: Int?) {
        if (resolution == null && fps == null) return
        val next = settingsEditor.update { current -> current.copy(
            videoResolutionId = resolution?.let { com.rawr.camera.video.VideoCrop.fromLegacy(it).persistedId() }
                ?: current.videoResolutionId,
            videoFps = fps?.takeIf { it == 24 || it == 30 || it == 60 } ?: current.videoFps
        ) }
        controller.dispatch(next.withLensOverrides().capturePreferences())
        settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoCrop)
    }

    private val mutableRenderProfiles = MutableStateFlow(settingsDefaults.toRenderProfileQuickState())
    val renderProfiles: StateFlow<RenderProfileQuickState> = mutableRenderProfiles.asStateFlow()
    private val mutableFilmSimQuick = MutableStateFlow(settingsDefaults.toFilmSimQuickState())
    val filmSimQuick: StateFlow<FilmSimQuickState> = mutableFilmSimQuick.asStateFlow()
    /** X14U: a lens's fixed f-number from its camera, cached by camera id. */
    private val apertureCache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private fun nativeApertures(values: SettingsValues): Map<String, Float> =
        values.effectiveLensProfiles(DeviceLensDefaults.lenses).mapNotNull { lens ->
            val id = lens.camera.physical.ifEmpty { lens.camera.id }
            val f = apertureCache[id] ?: runCatching {
                val manager = getApplication<Application>().getSystemService(android.hardware.camera2.CameraManager::class.java)
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull()
            }.getOrNull()?.also { apertureCache[id] = it }
            f?.let { lens.name to it }
        }.toMap()

    val viewfinderQuick: StateFlow<ViewfinderQuickState> = kotlinx.coroutines.flow.combine(settingsEditor.state, panoFlow, burstFlow) { s, pano, burst ->
        // Pano / Burst: the lens options as those modes run them ("50 mp" off, so the aperture is free).
        val values = if (pano || burst) s.copy(lensProfiles = s.lensProfiles?.withRunAsRowsOff()) else s
        values.withLensOverrides(runAs = !pano && !burst).toViewfinderQuickState(DeviceLensDefaults.lenses).copy(nativeApertures = nativeApertures(s))
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            latestSettings.toViewfinderQuickState(DeviceLensDefaults.lenses))
    private val galleryMediaStore = GalleryMediaStore(application)
    private val mutableLatestGalleryImage = MutableStateFlow<Uri?>(null)
    val latestGalleryImage: StateFlow<Uri?> = mutableLatestGalleryImage.asStateFlow()
    private val galleryRefreshGeneration = AtomicLong(0L)
    private val stillCaptureCoordinator =
        StillCaptureCoordinator(
            application = application,
            preview = previewCoordinator,
            // Zoomed past a lens with multiframe on: the "Merge when zoomed" choice (default: the usual merge).
            settings = {
                latestSettings.let { s ->
                    val zoomMerge = s.zoomMergeAlgorithm
                    when {
                        // Burst: every frame its own photo (no merge), on a lighter develop. A burst frame took 2.0 s
                        // on the photo path (diagnostics 14 Nov: AMaZE 0.82 s, render 0.67 s of which capture
                        // sharpening 0.21 s, UltraHDR JPEG at 100 0.53 s); RCD, no sharpening, plain JPEG at 95.
                        burstFlow.value -> s.copy(
                            experimentalMultiframeEnabled = false,
                            demosaicAlgorithm = com.rawr.camera.settings.model.DemosaicAlgorithm.Rcd,
                            photoCaptureSharpeningEnabled = false,
                            ultraHdrEnabled = false,
                            imageTone = s.imageTone.copy(jpegQuality = 95f)
                        )
                        // Timed burst shooting single frames (merge Off, or Auto at a short interval).
                        com.rawr.camera.burst.TimedBurstSettings.runSingleFrame -> s.copy(experimentalMultiframeEnabled = false)
                        digitalZoomCrop > 1.01f && s.experimentalMultiframeEnabled && zoomMerge != null -> s.copy(
                            multiframeTuning = s.multiframeTuning.copy(mergeAlgorithm = zoomMerge)
                        )
                        else -> s
                    }
                }
            },
            onJpegPublished = ::onJpegPublished,
            onDngPublished = ::refreshLatestGalleryImage,
            gpsSnapshot = cameraLocation::snapshot,
            levelSnapshot = levelSensor::snapshot
        )
    val controller: CaptureScreenController =
        NativeCaptureScreenController(
            previewCoordinator,
            onCaptureQueued = stillCaptureCoordinator::enqueueCapture,
            requestStillCapture = stillCaptureCoordinator::capture,
            foregroundToken = foreground::token,
            pollStillCompletion = stillCaptureCoordinator::pollCompletion,
            onToneScrubbed = { parameter, value -> persistQuickTone(parameter, value) },
            onRenderExposureScrubbed = ::persistRenderExposure,
            onAgxControl = ::persistAgxControl,
            videoLocked = { videoSelectionLocked() },
            onRawCpuIngress = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        application,
                        "Camera frames use the CPU copy path on this device (slower, more battery)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onLensSelected = { lensId -> settingsEditor.update { it.copy(lastLensId = lensId) } },
            onBurstFinished = ::writeBurstManifest,
            onCapturePreferencesChanged = { before, after ->
                // HDR+ is remembered per lens. Mode and LOG switches change what tone settings mean, so they edit
                // the global settings directly.
                if (before.captureMode != after.captureMode || before.videoLogEnabled != after.videoLogEnabled) {
                    // X14U: each mode keeps its own Look / Exposure set (lens overrides still win).
                    settingsEditor.update { prev ->
                        prev.withCapturePreferenceChanges(before, after.copy(
                            experimentalMultiframeEnabled = before.experimentalMultiframeEnabled))
                            .withModeLookSwitch(prev)
                    }
                } else {
                    updateForLens(setOf(com.rawr.camera.settings.model.LensOverrides.HDR_PLUS)) {
                        it.withCapturePreferenceChanges(before, after)
                    }
                }
                settingsSync.updatePreviewCrop(after.captureMode, after.videoCrop)
            }
        )

    private val settingsSync by lazy {
        com.rawr.camera.integration.CaptureSettingsSync(
            viewModelScope,
            // Burst runs single frames: no multiframe ring, merge-count noise limit or HDR+ Bracketed headroom
            // (that one darkens the viewfinder the burst frames come from) carried over from Photo.
            kotlinx.coroutines.flow.combine(effectiveValues, burstFlow, panoFlow) { values, burst, pano ->
                val shots = if (burst) values.copy(experimentalMultiframeEnabled = false) else values
                // Pano and Burst shoot 12 MP frames: a "50 mp" pick from Photo is off there (its aperture lock too).
                if (burst || pano) shots.copy(lensProfiles = shots.lensProfiles?.withRunAsRowsOff()) else shots
            },
            { latestSettings }, previewCoordinator, controller
        )
    }

    var recordingOptions = com.rawr.camera.video.RecordingOptions()
    private val recordingBackend = com.rawr.camera.video.CaptureRecordingBackend(
        application, controller, previewCoordinator, { latestSettings }
    )
    val recording = com.rawr.camera.video.RecordingCoordinator(
        controller = controller,
        startRecorder = { gps -> recordingBackend.start(recordingOptions, gps) },
        clockNs = android.os.SystemClock::elapsedRealtimeNanos,
        foregroundToken = foreground::token,
        validateAccess = cameraLocation::validateAccess,
        admitGps = { if (latestSettings.locationTagging) cameraLocation.snapshot() else null },
        onSaved = { recorder ->
            // The new video becomes the gallery thumbnail (Nov 18: videos never showed there).
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                refreshLatestGalleryImage()
            }
            if (recordingOptions.inspectOutput) {
                recordingOptions = recordingOptions.copy(inspectOutput = false)
                recordingBackend.inspectSaved(recorder)
            }
        }
    )
    private var captureVisible = false
    private var previewStopJob: kotlinx.coroutines.Job? = null

    fun startPreview() {
        captureVisible = true
        viewModelScope.launch {
            previewStopJob?.join()
            if (captureVisible) previewCoordinator.start()
        }
    }

    fun setUiPresentationActive(active: Boolean) = previewCoordinator.setUiPresentationActive(active)

    fun stopPreviewAfterRecording() {
        captureVisible = false
        val stopping = recording.stop()
        previewStopJob = viewModelScope.launch {
            stopping.join()
            if (!captureVisible) previewCoordinator.stop()
        }
    }

    init {
        com.rawr.camera.burst.BurstSettings.init(application)
        com.rawr.camera.video.VideoLockSettings.init(application)
        com.rawr.camera.burst.TimedBurstSettings.init(application)
        com.rawr.camera.settings.model.FaceSettings.init(application)
        com.rawr.camera.settings.model.AccentSettings.init(application)
        com.rawr.camera.settings.model.FullSizeJpegSettings.init(application)
        viewModelScope.launch {
            // X14U "25MP": the shown lens's choice, read natively at the shutter (only 50 MP frames scale).
            kotlinx.coroutines.flow.combine(
                com.rawr.camera.settings.model.FullSizeJpegSettings.halfLenses,
                controller.state.map { it.selectedLensId }
            ) { half, lensId -> lensId.trim().lowercase() in half }
                .distinctUntilChanged()
                .collect { previewCoordinator.setFullSizeJpegHalf(it) }
        }
        viewModelScope.launch {
            com.rawr.camera.settings.model.FaceSettings.values.collect { previewCoordinator.setFaceOptions(it.focus, it.exposure) }
        }
        viewModelScope.launch {
            // A per-shot 50 MP job owns the switch while it runs (it puts it back itself).
            companionRunAs.collect { on -> if (fullSizeJob?.isActive != true) previewCoordinator.setRunAsForShot(on) }
        }
        refreshLatestGalleryImage()
        viewModelScope.launch { recordingBackend.recoverInterrupted() }
        viewModelScope.launch {
            effectiveValues.map { it.capturePreferences() }.distinctUntilChanged().collect { preferences ->
                controller.dispatch(preferences)
                settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoCrop)
            }
        }
        viewModelScope.launch {
            effectiveValues.map { it.toRenderProfileQuickState() }.distinctUntilChanged().collect {
                mutableRenderProfiles.value = it
            }
        }
        viewModelScope.launch {
            settingsEditor.values.distinctUntilChanged { before, after ->
                before.selectedFilmPresetId == after.selectedFilmPresetId &&
                    before.filmSimLook == after.filmSimLook && before.filmPresets == after.filmPresets
            }.collect { mutableFilmSimQuick.value = it.toFilmSimQuickState() }
        }
        viewModelScope.launch {
            settingsEditor.values.map { it.zoomUpscale }.distinctUntilChanged().collect {
                previewCoordinator.setDigitalZoom(digitalZoomCrop, it)
            }
        }
        // Presets: P0 (the baseline) is the settings in use when presets first appear, kept from then on.
        viewModelScope.launch {
            settingsEditor.values.first().let { v ->
                if (v.capturePresets.none { it.isBaseline }) settingsEditor.update { s ->
                    val (saved, back) = encodeForPreset(s, com.rawr.camera.settings.model.PresetGroup.entries.toSet())
                    back.copy(capturePresets = listOf(newBaseline(s).copy(settings = saved)) + s.capturePresets.filterNot { it.isBaseline })
                }
            }
        }
        // Once: each lens's photo DCP defaults to an imported Main profile (Tele on the 3.2x / 5x) when it has none,
        // and video goes back to the camera's own colour (a DCP costs video frames).
        viewModelScope.launch {
            settingsEditor.values.first().let { v ->
                if (!v.dcpDefaultsOnce) settingsEditor.update { s ->
                    val lenses = s.effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                        val tele = (com.rawr.camera.settings.model.ZoomChain.factorOf(lens.name) ?: 1f) >= 3f
                        val match = lens.image.availableDcps.firstOrNull {
                            it.name.contains(if (tele) "tele" else "main", ignoreCase = true)
                        }
                        lens.image { (if (dcp == null && match != null) selectDcp(match.file) else this).selectVideoDcp(null) }
                    }
                    s.copy(lensProfiles = lenses, dcpDefaultsOnce = true)
                }
            }
        }
        // Option A, once: in-sensor-zoom lenses (the 2x) go off so zooming crops the main camera without a switch.
        // Turned back on in Settings → Lenses, the 2x is a button again and a zoom step (1x -> 2x -> 3.2x).
        viewModelScope.launch {
            settingsEditor.values.first().let { v ->
                if (!v.inSensorZoomLensesOffOnce) {
                    settingsEditor.update { s ->
                        val lenses = s.effectiveLensProfiles(DeviceLensDefaults.lenses)
                        val crops = com.rawr.camera.settings.model.ZoomChain.inSensorCrops(lenses)
                        s.copy(
                            lensProfiles = if (crops.isEmpty()) s.lensProfiles
                            else lenses.map { if (it.name in crops) it.copy(enabled = false) else it },
                            inSensorZoomLensesOffOnce = true
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            // Pano frames are always full size (the merge wants every pixel).
            kotlinx.coroutines.flow.combine(settingsEditor.values.map { it.photoAspect }, panoFlow) { aspect, pano ->
                if (pano) com.rawr.camera.settings.model.PhotoAspect.Native.nativeRatio else aspect.nativeRatio
            }.distinctUntilChanged().collect { previewCoordinator.setOutputAspect(it) }
        }
        // Pano with the merge on needs JPEG frames: DNG only becomes DNG + JPEG there (and comes back after).
        viewModelScope.launch {
            var restoreDngOnly = false
            kotlinx.coroutines.flow.combine(panoFlow, settingsEditor.values.map { it.panoMerge }) { pano, merge -> pano && merge }
                .distinctUntilChanged()
                .collect { required ->
                    com.rawr.camera.model.CaptureOutputFormat.jpegRequired = required
                    val state = controller.state.value
                    val format = com.rawr.camera.model.CaptureOutputFormat.from(state.dngEnabled, state.jpegEnabled)
                    if (required && format == com.rawr.camera.model.CaptureOutputFormat.Dng) {
                        controller.dispatch(com.rawr.camera.architecture.CycleOutputFormat)
                        restoreDngOnly = true
                    } else if (!required && restoreDngOnly) {
                        restoreDngOnly = false
                        if (format == com.rawr.camera.model.CaptureOutputFormat.DngAndJpeg) {
                            repeat(2) { controller.dispatch(com.rawr.camera.architecture.CycleOutputFormat) }
                        }
                    }
                }
        }
        // Video shutter display (time or angle) for the shutter labels.
        viewModelScope.launch {
            settingsEditor.values.map { it.videoShutterAngle }.distinctUntilChanged().collect {
                com.rawr.camera.integration.NativeCapabilityProjection.videoShutterAsAngle = it
            }
        }
        // Video horizon lock: on in Video with the setting, range by lens; Full freezes its framing while recording.
        // Stabilisation shares its fixed crop: on top of a limited range's, inside Full's.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsEditor.values.map {
                    // The real-time profile's Stabilise / Horizon lock switch for the current video mode.
                    val allowed = it.videoProfileAllows(com.rawr.camera.video.VideoProfileFeature.Stabilise)
                    HorizonSettings(it.videoHorizonLock && allowed, it.horizonLockRange,
                        if (it.videoHorizonFollow && allowed) it.horizonSmoothing.seconds else 0f,
                        if (allowed) it.videoStabilize else com.rawr.camera.settings.model.VideoStabilize.Off)
                }.distinctUntilChanged(),
                controller.state.map { (it.captureMode == com.rawr.camera.model.CaptureMode.Video) to it.selectedLensId }
                    .distinctUntilChanged(),
                recording.state.map { it.recording }.distinctUntilChanged()
            ) { h, (video, lens), rec ->
                val factor = com.rawr.camera.settings.model.ZoomChain.factorOf(lens) ?: 1f
                // Follow uses ±15° (about 1.4x); Lock the chosen range (default ±15°).
                val resolved = if (h.follow > 0f) com.rawr.camera.settings.model.HorizonLockRange.Deg15
                    else h.range.resolved(factor)
                val degrees = if ((h.lock || h.follow > 0f) && video) resolved.degrees else 0
                val front = com.rawr.camera.ui.isFrontLens(lens, lens)
                val stab = h.stabilize != com.rawr.camera.settings.model.VideoStabilize.Off && video && !front
                val horizonCrop = if (degrees > 0) resolved.crop else 1f
                val crop = if (!stab || horizonCrop >= 1.99f) horizonCrop else horizonCrop * h.stabilize.crop
                // Back lenses: about 23 mm equivalent at 1x over a 4:3 frame's width (only used without a profile).
                HorizonConfig(degrees, crop, rec, front, h.follow, stab, h.stabilize.seconds, factor * .665f)
            }.distinctUntilChanged().collect { c ->
                horizonSensor.configure(c.degrees, c.crop, recording = c.recording, front = c.front,
                    followSeconds = c.followSeconds)
                // Stabilisation alone still needs the zoom (the horizon sensor is off then).
                if (c.degrees == 0) previewCoordinator.setHorizonLock(0f, if (c.stabilize) c.crop else 1f, true, 0f, false)
                stabilizerSensor.configure(c.stabilize, c.crop, c.stabilizeSeconds, rollToo = c.degrees == 0,
                    focalNorm = c.focalNorm)
                previewCoordinator.setStabilizer(c.stabilize, c.focalNorm)
            }
        }
        settingsSync.start()
    }

    fun refreshLatestGalleryImage() {
        val generation = galleryRefreshGeneration.incrementAndGet()
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) { galleryMediaStore.findLatestRawrImage() }
            if (galleryRefreshGeneration.get() == generation) mutableLatestGalleryImage.value = latest
        }
        // Back from the burst viewer (bursts deleted there): the burst thumbnail and its count follow the library.
        // With the timed burst on, the thumbnail shows the last run the same way.
        if (burstFlow.value || com.rawr.camera.burst.TimedBurstSettings.values.value.enabled) viewModelScope.launch(Dispatchers.IO) {
            val latest = com.rawr.camera.burst.BurstStore.latest(getApplication())
            latestBurstFolder = latest?.folder
            latestBurstFlow.value = latest?.coverFrame?.view?.let(Uri::fromFile)
            withContext(Dispatchers.Main) {
                controller.dispatch(com.rawr.camera.architecture.SyncBurstLastCount(latest?.frames?.size ?: 0))
            }
        }
    }

    private fun onJpegPublished(uri: Uri) {
        // Burst frames (Rawr's own storage) don't replace the gallery's latest photo; the first one shows as the
        // burst thumbnail while the rest save.
        if (uri.scheme == "file") {
            if (latestBurstFlow.value == null || latestBurstFolder == null ||
                !uri.path.orEmpty().contains("/$latestBurstFolder/")) {
                latestBurstFlow.value = uri
                latestBurstFolder = java.io.File(uri.path.orEmpty()).parentFile?.name
            }
            return
        }
        galleryMediaStore.rememberPublishedJpeg(uri)
        galleryRefreshGeneration.incrementAndGet()
        mutableLatestGalleryImage.value = uri
    }

    private fun videoSelectionLocked(): Boolean = recording.state.value.let { it.busy || it.recording }

    fun selectRenderProfileFromCapture(selection: com.rawr.camera.model.RenderProfileSelection) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        val next = settingsEditor.update { it.withRenderProfile(selection) }.withLensOverrides()
        mutableRenderProfiles.value = next.toRenderProfileQuickState()
        previewCoordinator.setColorRenderProfile(next.effectiveRenderProfileId(), next.regularLutProfileId())
        controller.dispatch(SyncToneControls.fromImageTone(next.activeImageTone()))
    }

    fun onFilmEvent(event: com.rawr.camera.architecture.CaptureFilmEvent) {
        val next = settingsEditor.update { current ->
            when (event) {
                is com.rawr.camera.architecture.CaptureFilmEvent.SelectPreset -> current.withFilmPreset(event.id)
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, event.value), quantize = true, enableGrain = true
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ResetNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, FilmSimSpecs.forParameter(event.parameter).defaultValue)
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubDiscrete -> {
                    if (event.index !in event.field.options().indices) current else current.withFilmDiscrete(
                        SetFilmSimDiscreteValue(event.field, event.field.storedValue(event.index))
                    )
                }
                is com.rawr.camera.architecture.CaptureFilmEvent.SetFlag -> current.withFilmFlag(
                    SetFilmSimFlag(event.flag, event.enabled)
                )
            }
        }
        mutableFilmSimQuick.value = next.toFilmSimQuickState()
        previewCoordinator.setFilmSimLook(next.filmSimLook.toFloatArray(), next.filmSimLook.toIntArray())
    }

    /** Rail edits go through settings; CaptureSettingsSync projects them onto the preview and stills. */
    fun onQuickEvent(event: CaptureQuickEvent) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        when (event) {
            is CaptureQuickEvent.SelectDcp, is CaptureQuickEvent.SelectLensOption -> {
                settingsEditor.update { c -> c.withLensQuickEvent(event) }
                return
            }
            else -> Unit
        }
        updateForLens { c ->
            val video = c.isVideo
            when (event) {
                is CaptureQuickEvent.SelectDcp, is CaptureQuickEvent.SelectLensOption -> c
                is CaptureQuickEvent.SetRawrAeOption -> c.copy(rawrAe = event.edit(c.rawrAe))
                CaptureQuickEvent.ToggleRawrAe -> c.copy(rawrAe = c.rawrAe.copy(enabled = !c.rawrAe.enabled))
                is CaptureQuickEvent.SetMetering -> c.copy(rawrAe = c.rawrAe.copy(metering = event.metering))
                is CaptureQuickEvent.SetHighlightMode -> {
                    val enabled = event.mode != HighlightQuickMode.Off
                    val method = when (event.mode) {
                        HighlightQuickMode.Off -> if (video) c.videoHighlightMethod else c.photoHighlightMethod
                        HighlightQuickMode.Propagation -> 0
                        HighlightQuickMode.Opposed -> 1
                    }
                    if (video) c.copy(videoHighlightEnabled = enabled, videoHighlightMethod = method)
                    else c.copy(photoHighlightEnabled = enabled, photoHighlightMethod = method)
                }
                is CaptureQuickEvent.SetHighlightThreshold -> event.value.coerceIn(0.5f, 2f).let {
                    if (video) c.copy(videoHighlightThreshold = it) else c.copy(photoHighlightThreshold = it)
                }
                is CaptureQuickEvent.SetHighlightCompression -> event.value.coerceIn(0f, 300f).let {
                    if (video) c.copy(videoHighlightCompression = it) else c.copy(photoHighlightCompression = it)
                }
            }
        }
    }

    /** Lens-list edits from the capture screen (DCP pill, option toggles); the video DCP in video mode. */
    private fun SettingsValues.withLensQuickEvent(event: CaptureQuickEvent): SettingsValues = when (event) {
        is CaptureQuickEvent.SelectDcp -> if (controller.state.value.selectedLensId != event.lensId) this else
            copy(lensProfiles = effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                if (lens.name != event.lensId) lens
                else if (isVideo) lens.image { selectVideoDcp(event.file) } else lens.image { selectDcp(event.file) }
            })
        is CaptureQuickEvent.SelectLensOption -> lensProfiles?.let { lenses ->
            copy(lensProfiles = lenses.map { lens ->
                if (lens.name != event.lensId) lens
                // Pano / Burst run "50 mp" off: an aperture picked there doesn't turn Photo's 50 mp off.
                else if (panoFlow.value || burstFlow.value) lens.copy(options = lens.options.mapIndexed { i, o ->
                    if (i == event.row && event.choice in o.choices.indices) o.copy(selected = event.choice) else o
                })
                else lens.selectOption(event.row, event.choice)
            })
        } ?: this
        else -> this
    }

    private fun persistQuickTone(parameter: ToneParameter, value: Int) {
        updateForLens { current ->
            current.editActiveProfileTone { it.withToneParameter(parameter, value.coerceIn(-100, 100).toFloat()) }
        }
    }

    private fun persistAgxControl(parameter: com.rawr.camera.settings.model.AgxParameter, value: Float?) {
        settingsEditor.update { current ->
            if (value == null) current.withAgxDefault(parameter) else current.withAgxValue(parameter, value)
        }
    }

    private fun persistRenderExposure(tenths: Int) {
        val ev = com.rawr.camera.model.TonemapControlContract.exposureEvFromTenths(tenths)
        updateForLens { current -> current.editActiveProfileTone { it.copy(renderExposure = ev) } }
    }

    /** Diagnostic camera-ID override; native routing remains authoritative. */

    fun selectCameraIdFromIntent(cameraId: String) {
        previewCoordinator.setPreferredCameraId(cameraId)
    }

    override fun onCleared() {
        horizonSensor.close()
        stabilizerSensor.close()
        levelSensor.setActive(false)
        thermalLog.setActive(false)
        recording.closeWhenIdle {
            stillCaptureCoordinator.closeWhenIdle {
                controller.close()
                previewCoordinator.close()
            }
        }
    }
}

private data class HorizonSettings(
    val lock: Boolean,
    val range: com.rawr.camera.settings.model.HorizonLockRange,
    val follow: Float,
    val stabilize: com.rawr.camera.settings.model.VideoStabilize
)

private data class HorizonConfig(
    val degrees: Int,
    val crop: Float,
    val recording: Boolean,
    val front: Boolean,
    val followSeconds: Float,
    val stabilize: Boolean,
    val stabilizeSeconds: Float,
    val focalNorm: Float
)
