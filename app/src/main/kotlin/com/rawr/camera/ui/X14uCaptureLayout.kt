package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import com.rawr.camera.architecture.TriggerCapture
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.key
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureTransitions
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.ScrubRenderExposure
import com.rawr.camera.architecture.ScrubTone
import com.rawr.camera.architecture.SelectLens
import com.rawr.camera.architecture.SetVideoCrop
import com.rawr.camera.architecture.SetCaptureMode
import com.rawr.camera.architecture.SetExposureCandidate
import com.rawr.camera.architecture.SetExposureMode
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.architecture.CloseFocusSelector
import com.rawr.camera.architecture.SetManualFocus
import com.rawr.camera.architecture.SetRenderExposureTenths
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.SetWhiteBalanceTempTint
import com.rawr.camera.architecture.ToggleVideoLog
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.DiscreteExposureCapability
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.ExposureParameter
import com.rawr.camera.model.FilmSimQuickParam
import com.rawr.camera.model.FilmSimQuickSection
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.FilmSimQuickSubsection
import com.rawr.camera.model.FilmStripItem
import com.rawr.camera.model.FocusLadder
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.TonemapCatalog
import com.rawr.camera.model.VideoHealth
import com.rawr.camera.model.VideoTakeSummary
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.model.agxStripState
import com.rawr.camera.model.capabilityFor
import com.rawr.camera.model.exposureOwners
import com.rawr.camera.model.focusDistanceLabel
import com.rawr.camera.model.formatFilmStripValue
import com.rawr.camera.model.mfNormalizedFromDiopters
import com.rawr.camera.model.requestedCandidateIdFor
import com.rawr.camera.model.zeroEvCandidateId
import com.rawr.camera.settings.model.LensOverrides
import com.rawr.camera.settings.model.RawrBalance
import com.rawr.camera.settings.model.RawrDigitalGain
import com.rawr.camera.settings.model.RawrMaxShutter
import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.RawrNightLook
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Exposure
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * X14U capture screen, laid out like the stock camera (measured on its screenshots; design of 2026-11-10):
 * top bar · viewfinder (12–68% of the screen, corner brackets) with the lens row inside its bottom and the Look /
 * Exposure buttons at its edges · exposure row (value over label) · control area (Photo / Video when idle, a ruler
 * or chips for the tapped value) · shutter row. Text is sized in dp so the system font size can't clip it.
 * In landscape nothing moves; each element's content turns in place.
 */
internal enum class X14uPanel { Look, Exposure }

private val Amber = Color(0xFFFFB74D)
internal val X14uFont = FontFamily.Default

/** Readout values: the system's condensed face (narrow digits, like stock), so turned values fit the row in landscape. */
private val X14uReadoutFont: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Typeface(
        android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    )
)

/** Text size in dp (immune to the system font scale). */
@Composable
internal fun dpText(value: Float): TextUnit = with(LocalDensity.current) { value.dp.toSp() }

/** The capture modes on the strip (Nov 19: Pro is a mode of its own next to Photo; the order is the user's). */
internal enum class X14uMode(val label: String) {
    Photo("Photo"), Pro("Pro"), Video("Video"), Burst("Burst"), Pano("Pano")
}

internal fun currentX14uMode(state: CaptureUiState, proUi: Boolean, pano: Boolean = false, burst: Boolean = false): X14uMode = when {
    state.captureMode == CaptureMode.Video -> X14uMode.Video
    pano -> X14uMode.Pano
    burst -> X14uMode.Burst
    proUi -> X14uMode.Pro
    else -> X14uMode.Photo
}

internal fun selectX14uMode(target: X14uMode, state: CaptureUiState, dispatch: CaptureDispatch, extras: CaptureExtras) {
    when (target) {
        X14uMode.Video -> {
            extras.onSetPano(false)
            extras.onSetBurst(false)
            if (state.captureMode != CaptureMode.Video) dispatch(SetCaptureMode(CaptureMode.Video))
        }
        X14uMode.Pano -> {
            extras.onSetBurst(false)
            extras.onSetPano(true)
            if (state.captureMode != CaptureMode.Photo) dispatch(SetCaptureMode(CaptureMode.Photo))
        }
        X14uMode.Burst -> {
            extras.onSetPano(false)
            extras.onSetBurst(true)
            if (state.captureMode != CaptureMode.Photo) dispatch(SetCaptureMode(CaptureMode.Photo))
        }
        else -> {
            extras.onSetPano(false)
            extras.onSetBurst(false)
            extras.onSetProUi(target == X14uMode.Pro)
            if (state.captureMode != CaptureMode.Photo) dispatch(SetCaptureMode(CaptureMode.Photo))
        }
    }
}

/** Front-facing lenses leave the lens row; the front-camera button switches to them. */
internal fun isFrontLens(id: String, name: String): Boolean =
    listOf(id, name).any { it.contains("front", ignoreCase = true) || it.contains("selfie", ignoreCase = true) }

/** A tappable cell of the exposure row (value over label). */
private data class X14uCell(
    val key: String,
    val value: String,
    val label: String,
    /** Manual / changed from default: value in the soft accent. */
    val changed: Boolean = false,
    val enabled: Boolean = true,
    /** Starts a new group (thin divider before it). */
    val groupStart: Boolean = false,
    /** On auto: a small A badge next to the label, the value is what the camera picked. */
    val auto: Boolean = false
)

/** What the control area shows for the selected cell. */
private sealed interface X14uEditor {
    data class Ruler(
        val count: Int,
        val index: Int,
        val valueText: String,
        val label: (Int) -> String?,
        val major: (Int) -> Boolean,
        val spacing: Dp = 10.dp,
        val enabled: Boolean = true,
        /** Null: no A button. */
        val auto: Boolean? = null,
        val onAuto: () -> Unit = {},
        val onReset: () -> Unit = {},
        val onDragStart: () -> Unit = {},
        val endIcons: Boolean = false,
        /** A small text button at the right end (WB: TINT / K); null for none. */
        val trailing: String? = null,
        val trailingActive: Boolean = false,
        val onTrailing: () -> Unit = {},
        val onIndex: (new: Int, old: Int) -> Unit
    ) : X14uEditor

    data class Chips(
        val labels: List<String>,
        val selected: Int,
        val enabled: Boolean = true,
        /** Chips drawn dimmed but still tappable (film settings whose effect is off). */
        val dim: Set<Int> = emptySet(),
        val onPick: (Int) -> Unit
    ) : X14uEditor

    data class Message(val text: String) : X14uEditor

    /** Chips on top and, when present, another editor under them (Photo focus: AF · AF-L · MF, ruler in MF). */
    data class Stacked(val top: Chips, val below: X14uEditor?) : X14uEditor
}

/** The preset sheet that saves a new preset (otherwise the sheet holds a preset's id). */
private const val PRESET_SAVE = "+save"
private const val FILM_SAVE = "+film"
private const val FILM_NEW = "+new"

private object X14uGeometry {
    /** Stock content height (status and navigation bars excluded) the dp values below were measured at. */
    val reference = 841.dp
}

@Composable
internal fun X14uCaptureLayout(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    onOpenSettings: () -> Unit,
    latestImageUri: Uri?,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit,
    onOpenLensSettings: (String) -> Unit,
    modifier: Modifier,
    filmQuick: FilmSimQuickState?,
    onFilmEvent: (CaptureFilmEvent) -> Unit,
    quick: ViewfinderQuickState,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    videoControls: VideoControlState,
    onToggleVideoRecording: () -> Unit
) {
    val isVideo = state.captureMode == CaptureMode.Video
    val videoLocked = videoControls.busy || videoControls.recording
    // Hold-to-lock video (Nov 17): holding the record button records with the chosen settings locked from that moment
    // (S and ISO through the exposure mode, focus through AF lock, WB at its current value), given back at the stop.
    val videoLockSettings by com.rawr.camera.video.VideoLockSettings.values.collectAsState()
    val faceSettings by com.rawr.camera.settings.model.FaceSettings.values.collectAsState()
    val videoLock = remember { X14uVideoLock() }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
    LaunchedEffect(videoControls.recording) {
        if (!videoControls.recording && videoLock.active) videoLock.release(latestState, dispatch)
    }
    val onVideoHoldLock: () -> Unit = {
        videoLock.lock(state, dispatch, videoLockSettings)
        onToggleVideoRecording()
    }
    // The grip's red button works the same way in Video (Nov 17): let go before the hold time and it records as
    // usual, keep holding and it records locked; a press while recording stops. (Off: a press records at once,
    // handled by the activity.)
    val gripHold = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val gripScope = androidx.compose.runtime.rememberCoroutineScope()
    val gripVideo by rememberUpdatedState(videoControls)
    val gripLockSettings by rememberUpdatedState(videoLockSettings)
    val gripInVideo by rememberUpdatedState(isVideo)
    val gripToggle by rememberUpdatedState(onToggleVideoRecording)
    val gripLock by rememberUpdatedState(onVideoHoldLock)
    LaunchedEffect(Unit) {
        com.rawr.camera.integration.GripEvents.actions.collect { action ->
            if (!gripInVideo || !gripLockSettings.enabled) return@collect
            when (action) {
                com.rawr.camera.integration.GripEvents.Action.Record -> {
                    if (gripVideo.recording) {
                        gripToggle()
                    } else if (!gripVideo.busy) {
                        gripHold[0]?.cancel()
                        gripHold[0] = gripScope.launch {
                            kotlinx.coroutines.delay(gripLockSettings.holdMs.toLong())
                            gripHold[0] = null
                            gripLock()
                        }
                    }
                }
                com.rawr.camera.integration.GripEvents.Action.RecordRelease -> {
                    val pending = gripHold[0] ?: return@collect
                    pending.cancel()
                    gripHold[0] = null
                    gripToggle()
                }
                else -> Unit
            }
        }
    }
    // Video recording, or a 50 MP shot running (its camera switch must not meet a lens change).
    // A burst run too (a lens or mode change mid-run would mix cameras).
    val recordingLock = (isVideo && videoLocked) || LocalCaptureExtras.current.fullSizeBusy || state.burst.running
    // The selected lens's 50 MP shot is on (1x "50 mp"): its aperture is held at f/2.
    val fiftyMp = LocalCaptureExtras.current.fullSizeShot
    // X14U video crop: the viewfinder takes the recording's shape (portrait: its height over its width).
    val videoAspect = if (isVideo) state.videoCrop.sizes().second.let { it.width.toFloat() / it.height } else 4f / 3f
    // Video: the status bar hides so the picture can run to the top of the screen, like stock.
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isVideo) {
        var context = hostView.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, hostView) }
        val statusBars = androidx.core.view.WindowInsetsCompat.Type.statusBars()
        if (isVideo) {
            bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(statusBars)
        } else {
            bars?.show(statusBars)
        }
        onDispose { bars?.show(statusBars) }
    }
    // With the status bar hidden, keep the controls clear of the camera cut-out.
    val cutoutTop = if (isVideo) WindowInsets.displayCutout
        .asPaddingValues().calculateTopPadding() else 0.dp
    // Luma scopes only on X14U: a saved RGB histogram / waveform becomes its luma version.
    LaunchedEffect(state.activeScopes, state.waveformMode) {
        val luma = lumaScopeMode(state.waveformMode)
        if (luma != state.waveformMode && state.activeScopes.isNotEmpty()) {
            dispatch(com.rawr.camera.architecture.SetSingleScope(state.activeScopes.first(), luma))
        }
    }
    val orientation = state.orientation
    var selected by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<X14uPanel?>(null) }
    // Look and Exposure panels open where they were last left (kept across launches): the Look tab, and the setting
    // last picked in each tab / in Exposure. First time: Profile, and Rawr / Device AE.
    val panelMemory = rememberPanelMemory()
    var lookTab by remember { mutableStateOf(panelMemory.lookTab) }
    var panelKey by remember { mutableStateOf<String?>(null) }
    var showNumbers by remember { mutableStateOf(false) }
    // WB editor: Kelvin ruler, or tint after TINT.
    var wbTint by remember { mutableStateOf(false) }
    // Digital zoom (Photo / Pro, rear lenses): a total zoom across the lens chain; the longest lens not above it
    // runs and the rest is a centred crop (viewfinder, metering, JPEG, DNG default crop).
    val zoomChain = LocalCaptureExtras.current.tools.zoomChain
    val chainLens = zoomChain.firstOrNull { it.first == state.selectedLensId }
    val zoomOn = !isVideo && !LocalCaptureExtras.current.pano && chainLens != null
    var zoom by remember { mutableStateOf(1f) }
    // A lens chosen some other way (mode, front/back, settings) starts at its own zoom.
    LaunchedEffect(state.selectedLensId, zoomChain) {
        val f = chainLens?.second ?: return@LaunchedEffect
        val next = zoomChain.firstOrNull { it.second > f + 1e-3f }?.second ?: Float.MAX_VALUE
        if (zoom < f * .97f || zoom >= next) zoom = f
    }
    val zoomCrop = if (zoomOn) (zoom / chainLens!!.second).coerceIn(1f, 8f) else 1f
    val extrasForZoom = LocalCaptureExtras.current
    LaunchedEffect(zoomCrop) { extrasForZoom.onDigitalZoom(zoomCrop) }
    val zoomLabel = if (zoomOn && zoomCrop > 1.005f) zoomText(zoom) else null
    // Pinch / ruler: switches up a lens at its zoom, back down a little below it (no flicker at the boundary).
    // When pinch / the ruler last moved the zoom: a lens they land on is left as it is (no snap off a hidden lens).
    val zoomGestureAt = remember { longArrayOf(0L) }
    // The widest zoom on the pill (its widest visible lens or digital focal length), set below: pinch and the ruler
    // stop there, so a hidden lens wider than it (12 mm hidden, 17 mm on the pill) isn't reached (Nov 17).
    val zoomFloor = remember { floatArrayOf(0f) }
    val setZoom: (Float) -> Unit = setZoom@{ target ->
        if (zoomChain.isEmpty()) return@setZoom
        zoomGestureAt[0] = android.os.SystemClock.uptimeMillis()
        val z = target.coerceIn(maxOf(zoomChain.first().second, zoomFloor[0]), com.rawr.camera.settings.model.ZoomChain.MAX_ZOOM)
        val current = chainLens
        val lens = when {
            current != null && z >= current.second * .97f &&
                zoomChain.none { it.second > current.second + 1e-3f && it.second <= z + 1e-3f } -> current.first
            else -> com.rawr.camera.settings.model.ZoomChain.resolve(zoomChain, z)?.first
        }
        zoom = maxOf(z, zoomChain.firstOrNull { it.first == lens }?.second ?: z)
        if (lens != null && lens != state.selectedLensId && !recordingLock) dispatch(SelectLens(lens))
    }

    // Focus loupe: opened by MF ruler use (closes ~1 s after) or a double-tap (stays; at that point).
    val loupeSettings = LocalCaptureExtras.current.tools.focusLoupe
    var loupeOpen by remember { mutableStateOf(false) }
    var loupeAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var mfTouch by remember { mutableStateOf(0L) }
    val onMfTouch: () -> Unit = {
        if (loupeSettings.onManualFocus && !isVideo) {
            if (!loupeOpen) loupeAt = null
            loupeOpen = true
            mfTouch = System.nanoTime()
        }
    }
    // The loupe is a Photo / Pro / Pano aid: it closes on a mode change (one opened in Video doesn't wait there).
    LaunchedEffect(state.captureMode) {
        loupeOpen = false
        loupeAt = null
    }
    LaunchedEffect(mfTouch) {
        if (mfTouch != 0L && loupeAt == null) {
            kotlinx.coroutines.delay(1200L)
            // Moved by hand meanwhile: it stays until closed.
            if (loupeAt == null) loupeOpen = false
        }
    }
    // Tools panel (swipe down on the viewfinder / top-bar arrow) and its open choice row.
    var toolsOpen by remember { mutableStateOf(false) }
    // Video crop panel (top bar crop chip).
    var cropOpen by remember { mutableStateOf(false) }
    var toolsChoice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isVideo, recordingLock) { if (!isVideo || recordingLock) cropOpen = false }
    // Video digital focal length: kept only while its lens is in use (a lens chosen another way starts uncropped).
    val videoStopButtons = LocalCaptureExtras.current.tools.lensButtons
    LaunchedEffect(state.selectedLensId, isVideo) {
        val crop = state.videoCrop
        if (!isVideo || crop.digitalZoom <= 1.001f) return@LaunchedEffect
        val kept = videoStopButtons.forMode(com.rawr.camera.settings.model.LensMode.Video).digital.filter { it.on }
            .mapNotNull { com.rawr.camera.settings.model.resolveDigitalStop(it.mm, zoomChain) }
            .any { it.lens == state.selectedLensId && kotlin.math.abs(it.crop - crop.digitalZoom) <= it.crop * .01f }
        if (!kept) dispatch(SetVideoCrop(crop.copy(digitalZoom = 1f)))
    }
    LaunchedEffect(state.captureMode, state.selectedLensId) {
        // The zoom ruler stays open through the lens switches zooming makes.
        if (!(selected == ZOOM_KEY && zoomOn)) selected = null
        panel = null
    }
    LaunchedEffect(selected) { if (selected != "wb") wbTint = false }
    val lensOptions = quick.lensOptions.firstOrNull { it.lensId == state.selectedLensId }
    // Presets: the strip (in the mode names' place) and its sheet: "save" or the id of the preset whose details show.
    var presetsOpen by remember { mutableStateOf(false) }
    // Timed burst (intervalometer) panel, from its tile in the tools panel.
    var timedOpen by remember { mutableStateOf(false) }
    var presetSheet by remember { mutableStateOf<String?>(null) }
    // X14U (Nov 26): the film recipe strip (FILM plate) and its sheets (FILM_SAVE or a recipe id).
    var filmOpen by remember { mutableStateOf(false) }
    var filmSheet by remember { mutableStateOf<String?>(null) }
    // The recipe editor (+ in the strip, or Edit in a recipe's details): FILM_NEW or the edited recipe's id.
    var filmEdit by remember { mutableStateOf<String?>(null) }
    // The chosen setting inside a film section (its chip; Nov 26 sections editor).
    var filmItem by remember { mutableStateOf<String?>(null) }
    val closeAll: () -> Boolean = {
        val open = selected != null || panel != null || toolsOpen || cropOpen || presetsOpen || presetSheet != null || timedOpen ||
            filmOpen || filmSheet != null
        selected = null
        filmOpen = false
        filmSheet = null
        panel = null
        toolsOpen = false
        cropOpen = false
        toolsChoice = null
        presetsOpen = false
        presetSheet = null
        timedOpen = false
        open
    }
    val openTools: (Boolean) -> Unit = { open ->
        if (open) {
            selected = null
            panel = null
            cropOpen = false
            presetsOpen = false
            presetSheet = null
            filmOpen = false
            filmSheet = null
            timedOpen = false
        }
        toolsOpen = open
        toolsChoice = null
    }
    val film = state.filmSimEnabled && !isVideo && filmQuick != null
    // The recipe editor takes the exposure row (film cells) and the slot (their editors), its header above.
    val filmEditing = filmEdit != null && film
    // Photo (not Pro): the AMSI row instead of the full exposure row.
    // Pano runs Photo's simpler row; its set locks the controls that must match frame to frame.
    val pano = !isVideo && LocalCaptureExtras.current.pano
    // Burst: hold the shutter to shoot (stills as Photo, one frame each).
    val burst = !isVideo && !pano && LocalCaptureExtras.current.burst
    val burstFps by com.rawr.camera.burst.BurstSettings.fps.collectAsState()
    val burstPre by com.rawr.camera.burst.BurstSettings.pre.collectAsState()
    // Pre-capture ring: armed in Burst while the screen is on and no run is going (a run freezes it; it re-arms
    // after); off on leaving Burst or the screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val screenStarted by lifecycle.currentStateFlow.collectAsState()
    val ringOn = burst && burstPre > 0 && !state.burst.running &&
        screenStarted.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val extrasForRing = LocalCaptureExtras.current
    LaunchedEffect(ringOn, burstPre, burstFps, state.selectedLensId) {
        if (ringOn) extrasForRing.onArmPrecapture(burstPre, burstFps)
        else if (!state.burst.running) extrasForRing.onArmPrecapture(0, burstFps)
    }
    var burstPreFill by remember { mutableStateOf(0f) }
    LaunchedEffect(ringOn, burstPre, burstFps) {
        burstPreFill = 0f
        while (ringOn) {
            burstPreFill = extrasForRing.precaptureHeld() / (burstPre * burstFps).coerceAtLeast(1).toFloat()
            kotlinx.coroutines.delay(250L)
        }
    }
    // Photo's own row (S · A · I); Pro, Pano and Burst use the Pro row (Nov 19).
    val simplePhoto = !isVideo && !LocalCaptureExtras.current.proUi && !pano && !burst
    // Lens pill memory: the focal length last in use per mode (digital ones too) comes back when the screen starts,
    // and a hidden lens is never landed on at its own focal length (start, mode change, a lens picked elsewhere): the
    // nearest focal length on the pill takes its place. Pinch and the ruler are left alone.
    val lensMemory = remember {
        hostView.context.getSharedPreferences("x14u_lens_memory", android.content.Context.MODE_PRIVATE)
    }
    val lensRestored = remember { mutableSetOf<String>() }
    val lensSet = LocalCaptureExtras.current.tools.lensButtons.forMode(com.rawr.camera.settings.model.LensMode.of(isVideo, pano))
    val memoryKey = if (isVideo) "video" else "photo"
    val pillStops = if (pano || chainLens == null) emptyList() else lensSet.digital.filter { it.on }
        .mapNotNull { com.rawr.camera.settings.model.resolveDigitalStop(it.mm, zoomChain) }
    zoomFloor[0] = (zoomChain.filter { it.first !in lensSet.hidden }.map { it.second } + pillStops.map { it.zoom })
        .minOrNull() ?: 0f
    val atOwnFocal = if (isVideo) state.videoCrop.digitalZoom <= 1.001f else !zoomOn || zoomCrop <= 1.005f
    val pillStopNow = pillStops.firstOrNull { stop ->
        if (isVideo) stop.lens == state.selectedLensId && kotlin.math.abs(state.videoCrop.digitalZoom - stop.crop) <= stop.crop * .01f
        else zoomOn && kotlin.math.abs(zoom - stop.zoom) <= stop.zoom * .01f
    }
    val goToStop: (com.rawr.camera.settings.model.ResolvedStop) -> Unit = { stop ->
        if (isVideo) {
            dispatch(SetVideoCrop(state.videoCrop.copy(digitalZoom = stop.crop)))
            if (stop.lens != state.selectedLensId) dispatch(SelectLens(stop.lens))
        } else {
            setZoom(stop.zoom)
        }
    }
    val goToLens: (String) -> Unit = { id ->
        zoomChain.firstOrNull { it.first == id }?.let { zoom = it.second }
        if (id != state.selectedLensId) dispatch(SelectLens(id))
    }
    LaunchedEffect(state.selectedLensId, memoryKey, lensSet, zoomChain, atOwnFocal, recordingLock, pano) {
        val lens = chainLens ?: return@LaunchedEffect
        if (recordingLock || pano || !atOwnFocal) return@LaunchedEffect
        val lensMm = com.rawr.camera.settings.model.LensButtons.mmOf(lens.second)
        val remembered = lensMemory.getInt(memoryKey, 0)
        val visibleLenses = zoomChain.filter { it.first !in lensSet.hidden }
            .map { it.first to com.rawr.camera.settings.model.LensButtons.mmOf(it.second) }
        // Start of the screen: back to the digital focal length last used on this lens.
        if (lensRestored.add(memoryKey)) {
            pillStops.firstOrNull { it.mm == remembered && it.lens == lens.first }?.let { goToStop(it); return@LaunchedEffect }
        }
        if (lens.first !in lensSet.hidden) return@LaunchedEffect
        // Pinch / ruler onto a hidden lens: it stays (its live zoom shows on it).
        if (android.os.SystemClock.uptimeMillis() - zoomGestureAt[0] < 1500L) return@LaunchedEffect
        val candidates: List<Pair<Any, Int>> = pillStops.map { it to it.mm } + visibleLenses.map { it.first to it.second }
        val target = candidates.firstOrNull { it.second == remembered }
            ?: candidates.minWithOrNull(compareBy<Pair<Any, Int>>({ kotlin.math.abs(it.second - lensMm) },
                // Equally near: a digital focal length on this lens first (no lens switch).
                { if ((it.first as? com.rawr.camera.settings.model.ResolvedStop)?.lens == lens.first) 0 else 1 },
                { -it.second }))
        when (val item = target?.first) {
            is com.rawr.camera.settings.model.ResolvedStop -> goToStop(item)
            is String -> goToLens(item)
            else -> Unit
        }
    }
    // What is on the pill now, remembered for next time (not mid-pinch, not before this mode's restore).
    LaunchedEffect(state.selectedLensId, pillStopNow, atOwnFocal, memoryKey) {
        val lens = chainLens ?: return@LaunchedEffect
        if (memoryKey !in lensRestored || pano) return@LaunchedEffect
        val mm = pillStopNow?.mm ?: if (atOwnFocal) com.rawr.camera.settings.model.LensButtons.mmOf(lens.second) else return@LaunchedEffect
        lensMemory.edit().putInt(memoryKey, mm).apply()
    }
    val panoSet = remember { PanoSet() }
    val panoMotion = rememberPanoMotion(pano)
    // Timed burst (Nov 17): with it on, the shutter in Photo / Pro starts a run (a photo every interval, focus and
    // WB locked, exposure too when asked); the shutter stops it. While it runs the row is locked like a pano set.
    val timedSettings by com.rawr.camera.burst.TimedBurstSettings.values.collectAsState()
    val timedRun = remember { TimedRun() }
    val timedReady = timedSettings.enabled && !isVideo && !pano && !burst
    var timedNow by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val timedState by rememberUpdatedState(state)
    val timedExtras = LocalCaptureExtras.current
    LaunchedEffect(timedRun.running) {
        while (timedRun.running) {
            val now = android.os.SystemClock.uptimeMillis()
            timedNow = now
            // Saves still going from earlier photos: wait a little rather than pile them up.
            if (now >= timedRun.nextAt && timedState.pendingCaptures.count { !it.terminal } < 4) {
                // Straight to the shutter: a self-timer would delay every photo (and the next press cancel its countdown).
                timedRun.shoot { if (!timedExtras.onFullSizeShot()) dispatch(com.rawr.camera.architecture.TriggerCaptureNow) }
                if (timedRun.finished) {
                    kotlinx.coroutines.delay(600L)
                    timedRun.end(timedState, dispatch)
                }
            }
            kotlinx.coroutines.delay(100L)
        }
    }
    // The screen stays on through a run.
    androidx.compose.runtime.DisposableEffect(timedRun.running) {
        hostView.keepScreenOn = timedRun.running
        onDispose { hostView.keepScreenOn = false }
    }
    // Leaving Photo / Pro, turning it off or leaving the screen ends a run.
    LaunchedEffect(timedReady, screenStarted.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
        if (timedRun.running && (!timedReady || !screenStarted.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))) {
            timedRun.end(timedState, dispatch)
        }
    }
    val timedPress: () -> Unit = {
        if (timedRun.running) timedRun.end(state, dispatch)
        else if (timedReady) {
            closeAll()
            timedRun.start(state, dispatch, timedSettings, hostView.context)
        }
    }
    // A pano set or a timed burst run locks the row (and the lens pill).
    val panoLocked = (pano && panoSet.inSet) || timedRun.running
    // A set locks the row: an open editor (EV's ruler, a pano choice) closes when it starts.
    LaunchedEffect(panoLocked) { if (panoLocked) selected = null }
    val panoScope = androidx.compose.runtime.rememberCoroutineScope()
    val panoExtras = LocalCaptureExtras.current
    val panoState by rememberUpdatedState(state)
    val panoContext = androidx.compose.ui.platform.LocalContext.current
    // A set needs two frames (Nov 18): one stopped after frame 1 drops that frame (it isn't a panorama) and says so.
    val panoDropSingle: () -> Unit = {
        if (panoSet.frames == 1) {
            panoExtras.onPanoUndo(panoSet.frameTag(1))
            android.widget.Toast.makeText(panoContext, "A panorama needs 2 frames or more: frame removed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val panoDone: () -> Unit = {
        // A finished set (stop or complete) is merged on the phone when that's on (Settings → JPEG).
        if (panoSet.frames >= 2) {
            panoExtras.onPanoMerge(panoSet.tag, panoSet.frames, panoSet.startedAtMillis, panoSet.spanDegrees, panoSet.heldVertical)
        }
        panoDropSingle()
        panoSet.end(panoState, dispatch)
    }
    // Each frame keeps a small copy of the viewfinder for the strip under it.
    val panoShootNow: () -> Unit = {
        panoSet.shoot(panoMotion(), dispatch)
        val n = panoSet.frames
        preview.snapshot { bitmap -> panoSet.putThumb(n, bitmap) }
    }
    // The shutter starts a set (frame 1 after the locks settle) and stops it; the frames between take themselves.
    val panoShoot: () -> Unit = shoot@{
        if (recordingLock || panoSet.arming) return@shoot
        if (panoSet.frames > 0) return@shoot panoDone()
        // No orientation yet (sensor silent, see rememberPanoMotion): a set started now would take frame 1, lock
        // focus, exposure and white balance, and never go on. Say so and leave everything as it is.
        if (panoMotion() == null) {
            android.util.Log.w("RawrPano", "PANO_NO_MOTION at the shutter: set not started")
            android.widget.Toast.makeText(panoContext, "Motion sensor not ready: try again in a moment", android.widget.Toast.LENGTH_SHORT).show()
            return@shoot
        }
        panoSet.start(panoState, dispatch, com.rawr.camera.settings.model.ZoomChain.factorOf(panoState.selectedLensId) ?: 1f)
        panoScope.launch {
            kotlinx.coroutines.delay(400L)
            if (panoSet.arming) panoShootNow()
        }
    }
    val panoFrame: () -> Unit = { if (panoSet.frames > 0) panoShootNow() }
    val panoUndo: () -> Unit = { panoSet.undo(panoState, dispatch)?.let(panoExtras.onPanoUndo) }
    // The grip's full press does what the shutter does here (start / stop a timed burst, a Pano frame).
    val gripShutter by rememberUpdatedState(
        when {
            timedReady || timedRun.running -> timedPress
            pano -> panoShoot
            else -> null
        }
    )
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.rawr.camera.integration.GripEvents.shutterOverride = { gripShutter?.let { it(); true } ?: false }
        onDispose { com.rawr.camera.integration.GripEvents.shutterOverride = null }
    }
    // Leaving Pano (another mode, Video) ends the set, keeping its frames.
    LaunchedEffect(pano) {
        if (!pano && panoSet.inSet) {
            panoDropSingle()
            panoSet.end(panoState, dispatch)
        }
    }
    // Photo, Pro and Video each remember their own exposure mode (A·M·S·I) and white balance: switching mode saves
    // the one left and restores the one entered (a mode not visited yet keeps the current ones; Photo starts on A).
    val uiMode = when { isVideo -> "video"; simplePhoto -> "photo"; else -> "pro" }
    val modeMemory = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var lastMode by remember { mutableStateOf(uiMode) }
    val exposureMode = state.exposureControl.mode
    val wbNow = "${state.whiteBalanceMode.name},${state.whiteBalanceTemperatureK},${state.whiteBalanceTint}"
    fun memoryOf(raw: String): Map<String, String> =
        raw.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    androidx.compose.runtime.SideEffect {
        if (lastMode == uiMode) {
            modeMemory.value = (memoryOf(modeMemory.value) + (uiMode to "${exposureMode.name}|$wbNow"))
                .entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }
    LaunchedEffect(uiMode) {
        if (lastMode == uiMode) return@LaunchedEffect
        lastMode = uiMode
        val saved = memoryOf(modeMemory.value)[uiMode]
        val exposureName = saved?.substringBefore('|') ?: if (uiMode == "photo") ExposureMode.Auto.name else null
        val target = exposureName?.let { name -> ExposureMode.entries.firstOrNull { it.name == name } }
        if (target != null && target != state.exposureControl.mode && target in state.capabilities.supportedExposureModes) {
            dispatch(SetExposureMode(target))
        }
        val wb = saved?.substringAfter('|', "")?.split(',')
        val wbMode = wb?.getOrNull(0)?.let { name -> WhiteBalanceMode.entries.firstOrNull { it.name == name } }
        val temp = wb?.getOrNull(1)?.toIntOrNull()
        val tint = wb?.getOrNull(2)?.toIntOrNull()
        if (wbMode != null && temp != null && tint != null) {
            val locked = CaptureTransitions.isWhiteBalanceLocked(state)
            when {
                wbMode == WhiteBalanceMode.ManualTempTint && !locked -> dispatch(LockWhiteBalance(temp, tint))
                wbMode == WhiteBalanceMode.ManualTempTint ->
                    if (temp != state.whiteBalanceTemperatureK || tint != state.whiteBalanceTint)
                        dispatch(SetWhiteBalanceTempTint(temp, tint))
                wbMode != state.whiteBalanceMode -> dispatch(SetWhiteBalanceMode(wbMode))
            }
        }
    }
    // Aperture, per mode and lens: each mode remembers its own f-number; a mode not visited yet this session (and
    // every mode after a restart) starts at f/2, the main lens's sharpest stop and the camera's own aperture.
    val apertureRowIndex = apertureRow(lensOptions)
    val apertureKey = "$uiMode:${lensOptions?.lensId}"
    val apertureMemory = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var lastApertureKey by remember { mutableStateOf<String?>(null) }
    val apertureSelected = lensOptions?.options?.getOrNull(apertureRowIndex)?.selected
    androidx.compose.runtime.SideEffect {
        if (lastApertureKey == apertureKey && apertureSelected != null) {
            apertureMemory.value = (memoryOf(apertureMemory.value) + (apertureKey to apertureSelected.toString()))
                .entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }
    LaunchedEffect(apertureKey, apertureRowIndex) {
        if (lastApertureKey == apertureKey) return@LaunchedEffect
        lastApertureKey = apertureKey
        val options = lensOptions ?: return@LaunchedEffect
        val option = options.options.getOrNull(apertureRowIndex) ?: return@LaunchedEffect
        val target = memoryOf(apertureMemory.value)[apertureKey]?.toIntOrNull()
            ?: option.choices.indexOfFirst { it.label.trim().equals("f/2", ignoreCase = true) }
        // A row locked by another choice (50 MP holds f/2) or unused in this mode is left alone.
        val free = apertureRowIndex !in options.locked && apertureRowIndex !in options.unavailable
        if (free && target in option.choices.indices && target != option.selected && !recordingLock) {
            onQuickEvent(CaptureQuickEvent.SelectLensOption(options.lensId, apertureRowIndex, target))
        }
    }
    // Presets (Nov 17): the badge above Look opens the strip where the mode names are. Applying one puts its settings
    // in (view model) and its camera part here: lens (and zoom, crop, aperture) first, then, once the lens is
    // running, exposure and white balance. What doesn't fit (a lens or mode this mode lacks) is skipped.
    val presetsUi = LocalCaptureExtras.current.presets
    val filmRecipes = LocalCaptureExtras.current.filmRecipes
    val filmRecipeActions = LocalCaptureExtras.current.filmRecipeActions
    // Film off or video while the recipe editor is open: it closes, the film settings stay as they are.
    LaunchedEffect(film) {
        if (!film && filmEdit != null) {
            filmRecipeActions.finishEdit()
            filmEdit = null
        }
    }
    val presetActions = LocalCaptureExtras.current.presetActions
    var pendingPreset by remember { mutableStateOf<com.rawr.camera.settings.model.PresetCamera?>(null) }
    val apertureLabelNow = lensOptions?.options?.getOrNull(apertureRowIndex)?.let { it.choices.getOrNull(it.selected)?.label?.trim() }
    val presetCameraNow: () -> com.rawr.camera.settings.model.PresetCamera = {
        val ec = state.exposureControl
        val caps = state.capabilities
        val owners = state.exposureOwners()
        val label = buildList {
            add(ec.mode.shortLabel)
            if (ExposureParameter.Shutter in owners) caps.shutter.candidate(ec.requestedShutterId)?.let { add(it.displayLabel) }
            if (ExposureParameter.Iso in owners) caps.iso.candidate(ec.requestedIsoId)?.let {
                add(if (it.displayLabel.all(Char::isDigit)) "ISO ${it.displayLabel}" else it.displayLabel)
            }
            if (ExposureParameter.Ev in owners) caps.ev.candidate(ec.requestedEvId)?.let { add("EV ${it.displayLabel}") }
        }.joinToString(" · ")
        com.rawr.camera.settings.model.PresetCamera(
            exposure = com.rawr.camera.settings.model.PresetExposure(
                ec.mode.name, ec.requestedShutterId, ec.requestedIsoId, ec.requestedEvId, label),
            whiteBalance = com.rawr.camera.settings.model.PresetWhiteBalance(
                state.whiteBalanceMode.name, state.whiteBalanceTemperatureK, state.whiteBalanceTint),
            lens = com.rawr.camera.settings.model.PresetLens(
                state.selectedLensId,
                if (zoomOn) zoom else 0f,
                if (isVideo) state.videoCrop.digitalZoom else 1f,
                apertureLabelNow
            )
        )
    }
    // Owners of S / ISO / EV in a mode (A: EV · M: SS, ISO · S: SS, EV · I: ISO, EV).
    fun ownersOf(mode: ExposureMode): Set<ExposureParameter> = when (mode) {
        ExposureMode.Auto -> setOf(ExposureParameter.Ev)
        ExposureMode.Manual -> setOf(ExposureParameter.Shutter, ExposureParameter.Iso)
        ExposureMode.ShutterPriority -> setOf(ExposureParameter.Shutter, ExposureParameter.Ev)
        ExposureMode.IsoPriority -> setOf(ExposureParameter.Iso, ExposureParameter.Ev)
    }
    // The camera part matches what is set now (anything this mode can't take counts as matching).
    val presetCameraMatches: (com.rawr.camera.settings.model.PresetCamera) -> Boolean = match@{ c ->
        c.exposure?.let { e ->
            val mode = ExposureMode.entries.firstOrNull { it.name == e.mode }
            // Photo and Pano don't run M (it counts as matching there).
            if (mode != null && mode in state.capabilities.supportedExposureModes && !(simplePhoto && mode == ExposureMode.Manual)) {
                val ec = state.exposureControl
                if (mode != ec.mode) return@match false
                val owners = ownersOf(mode)
                if (ExposureParameter.Shutter in owners && e.shutterId.isNotEmpty() && e.shutterId != ec.requestedShutterId &&
                    state.capabilities.shutter.candidate(e.shutterId) != null) return@match false
                if (ExposureParameter.Iso in owners && e.isoId.isNotEmpty() && e.isoId != ec.requestedIsoId &&
                    state.capabilities.iso.candidate(e.isoId) != null) return@match false
                if (ExposureParameter.Ev in owners && e.evId.isNotEmpty() && e.evId != ec.requestedEvId &&
                    state.capabilities.ev.candidate(e.evId) != null) return@match false
            }
        }
        c.whiteBalance?.let { w ->
            if (w.mode != state.whiteBalanceMode.name) return@match false
            if (w.mode == WhiteBalanceMode.ManualTempTint.name &&
                (w.kelvin != state.whiteBalanceTemperatureK || w.tint != state.whiteBalanceTint)) return@match false
        }
        c.lens?.let { l ->
            if (state.capabilities.lenses.none { it.id == l.lensId }) return@let
            if (l.lensId != state.selectedLensId) return@match false
            if (zoomOn && l.zoom > 0f && abs(zoom - l.zoom) > l.zoom * .01f) return@match false
            if (isVideo && abs(state.videoCrop.digitalZoom - l.videoCrop) > .01f) return@match false
            if (l.aperture != null && apertureLabelNow != null && !l.aperture.equals(apertureLabelNow, ignoreCase = true)) return@match false
        }
        true
    }
    val activePreset = presetsUi.active
    // P0 marks only settings changes (its exposure, WB and lens are where a reset goes, not a look to keep).
    val presetChanged = activePreset != null && pendingPreset == null &&
        (presetsUi.settingsChanged || (!activePreset.isBaseline && !presetCameraMatches(activePreset.camera)))
    LaunchedEffect(pendingPreset) {
        if (pendingPreset != null) {
            kotlinx.coroutines.delay(5_000L)
            pendingPreset = null
        }
    }
    LaunchedEffect(pendingPreset, state.selectedLensId, state.cameraContextGeneration, state.exposureApplied.shutter == null, lensOptions) {
        val c = pendingPreset ?: return@LaunchedEffect
        if (recordingLock) { pendingPreset = null; return@LaunchedEffect }
        c.lens?.let { l ->
            if (state.capabilities.lenses.none { it.id == l.lensId }) return@let
            if (l.lensId != state.selectedLensId) {
                val own = zoomChain.firstOrNull { it.first == l.lensId }?.second
                if (!isVideo && !pano && own != null) {
                    setZoom(if (l.zoom > 0f) l.zoom else own)
                } else {
                    dispatch(SelectLens(l.lensId))
                }
                // The rest once that lens runs.
                return@LaunchedEffect
            }
            if (zoomOn && l.zoom > 0f && abs(zoom - l.zoom) > l.zoom * .01f) setZoom(l.zoom)
            if (isVideo && abs(state.videoCrop.digitalZoom - l.videoCrop) > .01f) {
                dispatch(SetVideoCrop(state.videoCrop.copy(digitalZoom = l.videoCrop)))
            }
            val options = lensOptions
            val option = options?.options?.getOrNull(apertureRowIndex)
            if (l.aperture != null && options != null && option != null &&
                apertureRowIndex !in options.locked && apertureRowIndex !in options.unavailable) {
                val target = option.choices.indexOfFirst { it.label.trim().equals(l.aperture, ignoreCase = true) }
                if (target >= 0 && target != option.selected) {
                    onQuickEvent(CaptureQuickEvent.SelectLensOption(options.lensId, apertureRowIndex, target))
                }
            }
        }
        // Exposure values are the running camera's: wait for its first result after a lens switch.
        if (state.exposureApplied.shutter == null) return@LaunchedEffect
        c.exposure?.let { e ->
            val mode = ExposureMode.entries.firstOrNull { it.name == e.mode } ?: return@let
            if (mode !in state.capabilities.supportedExposureModes) return@let
            if (simplePhoto && mode == ExposureMode.Manual) return@let
            if (mode != state.exposureControl.mode) dispatch(SetExposureMode(mode))
            val owners = ownersOf(mode)
            val caps = state.capabilities
            if (ExposureParameter.Shutter in owners && caps.shutter.candidate(e.shutterId) != null)
                dispatch(SetExposureCandidate(ExposureParameter.Shutter, e.shutterId))
            if (ExposureParameter.Iso in owners && caps.iso.candidate(e.isoId) != null)
                dispatch(SetExposureCandidate(ExposureParameter.Iso, e.isoId))
            if (ExposureParameter.Ev in owners && caps.ev.candidate(e.evId) != null)
                dispatch(SetExposureCandidate(ExposureParameter.Ev, e.evId))
        }
        c.whiteBalance?.let { w ->
            val wbMode = WhiteBalanceMode.entries.firstOrNull { it.name == w.mode } ?: return@let
            val locked = CaptureTransitions.isWhiteBalanceLocked(state)
            when {
                wbMode == WhiteBalanceMode.ManualTempTint && !state.capabilities.manualWhiteBalanceSupported -> Unit
                wbMode == WhiteBalanceMode.ManualTempTint && !locked -> dispatch(LockWhiteBalance(w.kelvin, w.tint))
                wbMode == WhiteBalanceMode.ManualTempTint ->
                    if (w.kelvin != state.whiteBalanceTemperatureK || w.tint != state.whiteBalanceTint)
                        dispatch(SetWhiteBalanceTempTint(w.kelvin, w.tint))
                wbMode != state.whiteBalanceMode -> dispatch(SetWhiteBalanceMode(wbMode))
            }
        }
        pendingPreset = null
    }
    val applyPreset: (PresetItem) -> Unit = { item ->
        presetActions.apply(item.id)
        val c = item.camera
        pendingPreset = if (c.exposure == null && c.whiteBalance == null && c.lens == null) null else c
    }
    // Long-press on the AF target: Photo opens AF · AF-L · MF below the exposure row, Pro opens its focus cell.
    LaunchedEffect(state.focus.selectorOpen) {
        if (state.focus.selectorOpen) {
            panel = null
            selected = if (simplePhoto) PHOTO_FOCUS_KEY else "af"
            dispatch(CloseFocusSelector)
        }
    }
    // Photo has no focus control: entering it returns focus to continuous AF.
    LaunchedEffect(simplePhoto) {
        if (simplePhoto && state.focus.mode != FocusMode.Af) dispatch(SetFocusMode(FocusMode.Af))
    }
    // Photo and Pano run S · A · I (Nov 19): full manual is the Pro mode, so M left over (a preset, Pro's last mode)
    // turns to A.
    LaunchedEffect(simplePhoto, state.exposureControl.mode) {
        if (simplePhoto && state.exposureControl.mode == ExposureMode.Manual &&
            ExposureMode.Auto in state.capabilities.supportedExposureModes) dispatch(SetExposureMode(ExposureMode.Auto))
    }

    // Cells and editors for what is showing.
    val cells: List<X14uCell>
    val editorFor: (String) -> X14uEditor
    when (panel) {
        null -> if (filmEditing) {
            val q = filmQuick!!
            cells = filmSectionCells(q)
            editorFor = { key -> filmSectionEditor(key, q, filmActions(onFilmEvent), filmItem) { filmItem = it } }
        } else {
            val all = exposureCells(state, quick, lensOptions, fiftyMp)
            val photoCells = if (simplePhoto) {
                // Photo: only the cells the exposure mode lets you set (A: EV · M: SS, ISO · S: SS, EV · I: ISO, EV), plus F.
                val owners = state.exposureOwners().map {
                    when (it) {
                        ExposureParameter.Ev -> "ev"
                        ExposureParameter.Shutter -> "ss"
                        ExposureParameter.Iso -> "iso"
                    }
                }
                owners.mapNotNull { k -> all.firstOrNull { it.key == k } } + all.filter { it.key == "f" }
            } else all
            cells = photoCells
            editorFor = { key ->
                if (key == ZOOM_KEY) zoomRuler(zoom, zoomChain, setZoom)
                else if (key == PHOTO_FOCUS_KEY) photoFocusEditor(state, dispatch, onMfTouch)
                else if (key == "f" && fiftyMp && apertureRow(lensOptions) >= 0) X14uEditor.Message("f/2 while 50 MP is on")
                else exposureEditor(key, state, dispatch, lensOptions, recordingLock, onQuickEvent, wbTint, { wbTint = !wbTint }, onMfTouch)
            }
        }
        X14uPanel.Exposure -> {
            cells = aeCells(quick, faceSettings.exposure)
            editorFor = { key -> aeEditor(key, quick, onQuickEvent) }
        }
        X14uPanel.Look -> {
            val tabs = lookTabs(film)
            val tab = lookTab.takeIf { it in tabs } ?: "PROFILE"
            when (tab) {
                "PROFILE" -> {
                    val label = renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }?.label ?: "RAWR NTRL"
                    cells = listOf(X14uCell("profile", label, if (renderProfiles.log) "LOG PROFILE" else "PROFILE"))
                    // LOG on lists the LOG profiles, LOG off the usual ones.
                    val options = renderProfiles.options
                    editorFor = {
                        X14uEditor.Chips(
                            options.map { it.label },
                            options.indexOfFirst { it.id == renderProfiles.selectedId },
                            enabled = !renderProfiles.locked
                        ) { i -> onSelectRenderProfile(options[i].id) }
                    }
                }
                "HL" -> {
                    val hl = quick.highlight
                    cells = listOf(
                        X14uCell("hl.method", listOf("Off", "Propagation", "Opposed")[hl.mode.ordinal], "METHOD",
                            changed = hl.mode != HighlightQuickMode.Off),
                        X14uCell("hl.compression", hl.compression.roundToInt().toString(), "COMPRESSION",
                            changed = hl.compression != hl.compressionDefault, enabled = hl.mode != HighlightQuickMode.Off)
                    )
                    editorFor = { key -> highlightEditor(key, quick, onQuickEvent) }
                }
                "FILM" -> {
                    val q = filmQuick!!
                    cells = listOf(X14uCell("film.preset", q.activePresetName, "PRESET", changed = q.modified)) +
                        filmSectionCells(q)
                    editorFor = { key ->
                        if (key == "film.preset") {
                            X14uEditor.Chips(q.presets.map { it.label }, q.presets.indexOfFirst { it.id == q.selectedPresetId }) { i ->
                                onFilmEvent(CaptureFilmEvent.SelectPreset(q.presets[i].id))
                            }
                        } else {
                            filmSectionEditor(key, q, filmActions(onFilmEvent), filmItem) { filmItem = it }
                        }
                    }
                }
                else -> {
                    val agx = renderProfiles.agx
                    val profileLabel = renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }?.label ?: "AgX"
                    val q = if (agx != null) agxStripState(agx, state.renderExposureTenths, profileLabel) else toneStripState(state)
                    cells = if (renderProfiles.log) listOf(X14uCell("tone.log", "—", "NO TONE IN LOG", enabled = false))
                    else configCells(q)
                    val actions = if (agx != null) agxActions(dispatch) else toneActions(state, dispatch)
                    editorFor = { key -> configEditor(key, q, actions) }
                }
            }
        }
    }
    // Grip zoom lever with Smooth zoom (Nov 19, Photo / Pro): held, it zooms steadily (a doubling about every 1.2 s)
    // through the lenses as the zoom ruler does, with the ruler open to show where it is; it closes 1.5 s after the
    // lever is let go (if the lever opened it). Elsewhere, or with Lens steps, the lever steps along the pill.
    val leverContext = LocalContext.current
    remember(leverContext) { com.rawr.camera.settings.model.GripLeverSettings.init(leverContext) }
    val leverZooms = zoomOn && !recordingLock &&
        com.rawr.camera.settings.model.GripLeverSettings.mode == com.rawr.camera.settings.model.GripLeverSettings.Mode.Zoom
    androidx.compose.runtime.DisposableEffect(leverZooms) {
        com.rawr.camera.integration.GripEvents.leverZooms = leverZooms
        onDispose { com.rawr.camera.integration.GripEvents.leverZooms = false }
    }
    val leverHeld by com.rawr.camera.integration.GripEvents.zoomHeld.collectAsState()
    val zoomNow by rememberUpdatedState(zoom)
    val setZoomNow by rememberUpdatedState(setZoom)
    var leverOpened by remember { mutableStateOf(false) }
    LaunchedEffect(leverHeld, leverZooms) {
        if (!leverZooms) return@LaunchedEffect
        if (leverHeld == 0) {
            if (leverOpened) {
                kotlinx.coroutines.delay(1500L)
                if (selected == ZOOM_KEY) selected = null
                leverOpened = false
            }
            return@LaunchedEffect
        }
        if (selected != ZOOM_KEY) {
            panel = null
            selected = ZOOM_KEY
            leverOpened = true
        }
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val seconds = (now - last) / 1e9f
            last = now
            setZoomNow(zoomNow * Math.pow(2.0, (leverHeld * seconds / 1.2f).toDouble()).toFloat())
        }
    }
    // Photography Kit dial (read over USB): clicks move the chosen control's ruler or choices, which open to show it.
    val gripTools = LocalCaptureExtras.current.tools
    val dialStep by androidx.compose.runtime.rememberUpdatedState { clicks: Int ->
        val key = gripTools.gripDial.key.let { if (it == "zoom") ZOOM_KEY else it }
        val steps = if (gripTools.gripDialReverse) -clicks else clicks
        val editor = when {
            panoLocked || (recordingLock && key in setOf("f", ZOOM_KEY)) -> null
            key == ZOOM_KEY -> if (zoomOn) zoomRuler(zoom, zoomChain, setZoom) else null
            else -> exposureEditor(key, state, dispatch, lensOptions, recordingLock, onQuickEvent, wbTint, { wbTint = !wbTint }, onMfTouch)
        }
        when (editor) {
            is X14uEditor.Ruler -> if (editor.enabled) {
                val next = (editor.index + steps).coerceIn(0, editor.count - 1)
                if (next != editor.index) editor.onIndex(next, editor.index)
                panel = null
                selected = key
            }
            is X14uEditor.Chips -> if (editor.enabled && editor.labels.isNotEmpty()) {
                val next = (editor.selected + steps).coerceIn(0, editor.labels.lastIndex)
                if (next != editor.selected) editor.onPick(next)
                panel = null
                selected = key
            }
            else -> Unit
        }
    }
    LaunchedEffect(Unit) { com.rawr.camera.integration.GripEvents.dialSteps.collect { dialStep(it) } }
    val currentKey = if (panel == null && filmEditing) selected?.takeIf { k -> cells.any { it.key == k } } ?: cells.firstOrNull()?.key
    else if (panel == null) selected?.takeIf { k -> (k == PHOTO_FOCUS_KEY && simplePhoto) || (k == ZOOM_KEY && zoomOn) || cells.any { it.key == k } } else (panelKey?.takeIf { k -> cells.any { it.key == k } } ?: cells.firstOrNull()?.key)

    BoxWithConstraints(modifier) {
        val s = ((maxHeight - cutoutTop) / X14uGeometry.reference).coerceIn(.8f, 1.25f)
        val top = cutoutTop + maxOf(CaptureDimens.TopBarHeight + 4.dp, 65.dp * s)
        val frameWidth = minOf(maxWidth - 16.dp, 379.dp * s, (maxHeight * .6f) * .75f)
        val frame = DpSize(frameWidth, frameWidth * 4f / 3f)
        val bottom = top + frame.height
        val fullHeight = maxHeight
        val shutterCenter = minOf(bottom + 175.dp * s, fullHeight - 50.dp)
        // Video, like stock: the recording's shape at the photo frame's width. A short one (4:3, 1:1) sits where the
        // photo frame does; a tall one starts right under the cut-out with the top bar over it and runs down to just
        // above the shutter (16:9); taller still (1.85 to 2.39) it goes on under the shutter row, to 50 dp below the
        // shutter's centre, and only then narrows (Nov 18, both styles). The controls sit on it as in Photo.
        // Muted: no meter (an empty box says nothing); the histogram moves up into its place.
        val audioMeterOn = isVideo && LocalCaptureExtras.current.tools.audioMeter && !videoControls.audio.muted
        val classic = isClassicStyle()
        // The readout row (Nov 18, landscape v3): from just under the picture to the slot, so turned values fit.
        val rowHeight = 56.dp * s
        val (videoRect, videoTop) = run {
            val upper = cutoutTop + 2.dp
            val lower = shutterCenter - 50.dp
            var w = frameWidth
            var h = w * videoAspect
            val room = if (h <= lower - upper) lower - upper else minOf(shutterCenter + 50.dp, fullHeight) - upper
            if (h > room) { h = room; w = h / videoAspect }
            DpSize(w, h) to (if (top + h <= lower) top else upper)
        }
        // The readout row and the slot under it (modes, or the open control).
        val rowY = bottom + 2.dp * s
        // Presets open: the strip takes the readout row and the slot (the exposure row and lens row hide, as stock).
        // The film recipe strip takes the same place (photo with film on).
        val filmStripOpen = filmOpen && film && !filmEditing
        val presetsShown = (presetsOpen || filmStripOpen) && !recordingLock && !panoLocked
        // Pano set (Nov 19): the panorama forming takes the readout row and the slot.
        val panoStripShown = pano && panoSet.frames > 0
        // The preset nameplate goes under the shutter row when there's room (Nov 19), else above the Look button.
        val plateCenter = minOf(shutterCenter + 58.dp, fullHeight - 24.dp)
        val plateBelow = plateCenter - 13.dp >= shutterCenter + 40.dp
        val slotIdle = !(burst && state.burst.running) && !timedRun.running && !presetsShown &&
            currentKey == null && panel == null
        val slotY = rowY + rowHeight
        val slotHeight = 76.dp * s
        val layoutWidth = maxWidth
        // Recording: what can't be used meanwhile (lens row, Look / AE, badge, modes, top bar, the shutter row's side
        // buttons) dims so the picture and the exposure readouts stand out (Nov 18).
        val recordingDim = if (isVideo && videoControls.recording) .3f else 1f
        // Video whose foot runs under the slot (Minimal's tall video): the slot steps in past the bottom corner marks
        // (22 dp) so the A button and the trailing one sit clear of them (Nov 18).
        val videoFoot = videoTop + videoRect.height
        val slotInset = if (isVideo && videoFoot > slotY && videoFoot - 22.dp < slotY + slotHeight)
            maxOf(12.dp, (layoutWidth - videoRect.width) / 2 + 22.dp + 6.dp) else 12.dp
        Box(Modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val classicPlan = if (!classic) null else {
                val picSize = if (isVideo) videoRect else frame
                val picTop = if (isVideo) videoTop else top
                with(density) {
                    val left = ((layoutWidth - picSize.width) / 2).toPx()
                    val picture = androidx.compose.ui.geometry.Rect(left, picTop.toPx(), left + picSize.width.toPx(), (picTop + picSize.height).toPx())
                    val barTop = cutoutTop.toPx()
                    val barBottom = (cutoutTop + CaptureDimens.TopBarHeight).toPx()
                    ClassicBodyPlan(
                        topBar = barTop..barBottom,
                        // Video: the bezel ends where the photo frame's does (leather beside a picture running lower).
                        bezel = barBottom..minOf(picture.bottom + 2.dp.toPx(), (bottom + 2.dp).toPx()),
                        picture = picture,
                        readouts = (rowY + 2.dp).toPx()..(if (presetsShown || panoStripShown) slotY + slotHeight else rowY + rowHeight).toPx(),
                        band = if (slotIdle) slotY.toPx()..(slotY + slotHeight).toPx() else null
                    )
                }
            }
            classicPlan?.let { X14uClassicBody(it, Modifier.fillMaxSize()) }
            CaptureViewfinder(
                state = state,
                dispatch = dispatch,
                preview = preview,
                renderProfiles = renderProfiles.copy(locked = recordingLock),
                onSelectRenderProfile = onSelectRenderProfile,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = if (isVideo) videoTop else top)
                    .size(if (isVideo) videoRect else frame),
                slotFrame = frame,
                slotOffsetY = if (isVideo) top - videoTop else 0.dp,
                // Video: below the top bar; photo: just inside the frame. The sound meter sits on the histogram's
                // top edge as the phone is held (Settings → Video can turn it off).
                scopeTopInset = if (isVideo) top + 4.dp - videoTop else 8.dp,
                scopeHeader = if (!audioMeterOn) null else ({
                    AudioMeter(videoControls.audio, recording = videoControls.recording, compact = true)
                }),
                forceCompact = isVideo,
                forceTonemapStrip = isVideo,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick.copy(locked = recordingLock),
                onQuickEvent = onQuickEvent,
                x14uClean = true,
                onViewfinderTap = closeAll,
                onViewfinderPinch = if (!zoomOn) null else { factor -> setZoom(zoom * factor) },
                onViewfinderDoubleTap = if (!loupeSettings.doubleTap || isVideo) null else { fx, fy ->
                    if (loupeOpen) {
                        loupeOpen = false
                    } else {
                        loupeAt = androidx.compose.ui.geometry.Offset(fx, fy)
                        loupeOpen = true
                    }
                },
                onViewfinderSwipe = { direction ->
                    when {
                        direction > 0 -> openTools(true)
                        toolsOpen -> openTools(false)
                    }
                }
            )
            // Classic video: the plates the picture runs under turn to smoked glass over it.
            if (isVideo) classicPlan?.let { X14uClassicGlass(it, Modifier.fillMaxSize()) }
            // Video: the corner marks sit on the picture's own corners, all four (the open control steps in from the
            // bottom ones instead: slotInset).
            if (isVideo) FrameBrackets(Modifier.align(Alignment.TopCenter).offset(y = videoTop).size(videoRect))

            Box(Modifier.align(Alignment.TopCenter).offset(y = top).size(frame)) {
                if (!isVideo) FrameBrackets(Modifier.fillMaxSize())
                val tools = LocalCaptureExtras.current.tools
                if (!isVideo && !pano) X14uAspectMask(tools.photoAspect, Modifier.fillMaxSize())
                if (pano) X14uPanoOverlay(
                    set = panoSet,
                    motion = panoMotion,
                    orientation = orientation,
                    onFrame = panoFrame,
                    onFinish = panoDone,
                    modifier = Modifier.fillMaxSize()
                )
                if (tools.level) X14uLevel(Modifier.fillMaxSize())
                // Status badges in one place, clear of the histogram (top right as held; Nov 17 the timed badge sat on it
                // in landscape): portrait top left; landscape the top right as held (the screen's bottom right, above
                // the lens row). A line per mode badge (level lock / stabilise, timed burst), then the locks in force.
                run {
                    val stabBadge = isVideo && tools.stabilize != com.rawr.camera.settings.model.VideoStabilize.Off &&
                        !isFrontLens(state.selectedLensId, state.selectedLensId)
                    val lines = listOfNotNull(
                        if (isVideo && (tools.horizonLock || tools.horizonFollow || stabBadge)) listOfNotNull(
                            if (tools.horizonFollow) "LEVEL FOLLOW" else if (tools.horizonLock) "LEVEL LOCK" else null,
                            if (stabBadge) "STAB" else null
                        ).joinToString(" · ") else null,
                        if (timedRun.running) "TIMED · ${timedRun.shots} / ${if (timedRun.total > 0) "${timedRun.total}" else "∞"}"
                        else if (timedReady) "TIMED ${timedBurstShort(timedSettings)}" else null
                    )
                    val chips = when {
                        isVideo && videoControls.recording && videoLock.active -> videoLock.chips
                        timedRun.running -> timedRun.locks
                        else -> emptyList()
                    }
                    // 3x 50 MP: the viewfinder is the camera's own processing; the look applies to the photo.
                    val cameraPreview = state.companionViewfinder && !isVideo
                    if (lines.isNotEmpty() || chips.isNotEmpty() || cameraPreview) {
                        Column(
                            (if (orientation == Orientation.Landscape) Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 64.dp)
                                else Modifier.align(Alignment.TopStart).padding(8.dp))
                                .uprightLayoutInLandscape(orientation),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (cameraPreview) Text(
                                "Camera preview", fontFamily = X14uFont, fontSize = dpText(11f),
                                color = Color.White.copy(alpha = .85f),
                                modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            lines.forEach { line ->
                                Text(
                                    line, fontFamily = CaptureMono, fontSize = dpText(10.5f), color = CaptureColors.Accent,
                                    modifier = Modifier.background(Color.Black.copy(alpha = .5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                            if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                chips.forEach { chip ->
                                    Text(
                                        "🔒 $chip", fontFamily = X14uFont, fontSize = dpText(11f), color = Color.White,
                                        modifier = Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // Pano merge progress / result (keeps showing in other modes while it runs).
                LocalCaptureExtras.current.panoStatus?.let { message ->
                    Text(
                        message,
                        fontFamily = X14uFont,
                        fontSize = dpText(11.5f),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            // Below the capture status (56 dp from the top as held).
                            .align(if (orientation == Orientation.Landscape) Alignment.CenterEnd else Alignment.TopCenter)
                            .padding(if (orientation == Orientation.Landscape) PaddingValues(end = 96.dp) else PaddingValues(top = 96.dp))
                            .uprightLayoutInLandscape(orientation)
                            .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                // Photo / Pro only: in Video the viewfinder runs past this frame (16:9), so the loupe would sit off.
                if (loupeOpen && !isVideo) {
                    val target = state.focus.target
                    X14uFocusLoupe(
                        center = loupeAt ?: androidx.compose.ui.geometry.Offset(target.x, target.y),
                        settings = loupeSettings,
                        preview = preview,
                        distanceLabel = if (state.focus.mode == FocusMode.Mf) state.focusDistanceLabel() else null,
                        orientation = orientation,
                        onClose = { loupeOpen = false },
                        onMoveCenter = { loupeAt = it },
                        // Let go: focus there (AF / AF-L; MF keeps its distance, peaking shows in the loupe). Not
                        // during a pano set, whose focus is locked.
                        onMoveEnd = { spot ->
                            if (state.focus.mode != FocusMode.Mf && !panoLocked) {
                                dispatch(com.rawr.camera.architecture.FocusAt(com.rawr.camera.model.NormalizedPoint(spot.x, spot.y)))
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isVideo && videoControls.recording) {
                    X14uRecordTimer(
                        video = videoControls,
                        orientation = orientation,
                        onTap = { if (videoControls.diagnostics) showNumbers = !showNumbers },
                        // Top centre as the phone is held (landscape: the frame's right edge).
                        modifier = if (orientation == Orientation.Landscape) Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                            else Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    )
                }
                if (!presetsShown && !(panel == null && filmEditing)) X14uLensRow(
                    state = state,
                    dispatch = dispatch,
                    quick = quick,
                    panel = panel,
                    lookTabs = lookTabs(film),
                    lookTab = lookTab,
                    onLookTab = {
                        lookTab = it
                        panelMemory.lookTab = it
                        panelKey = panelMemory.keyFor("look.$it")
                    },
                    enabled = !recordingLock && !panoLocked,
                    onTogglePanel = { target ->
                        selected = null
                        presetsOpen = false
                        presetSheet = null
                        filmOpen = false
                        filmSheet = null
                        panel = if (panel == target) null else target
                        panelKey = when (panel) {
                            X14uPanel.Look -> panelMemory.keyFor("look.$lookTab")
                            X14uPanel.Exposure -> panelMemory.keyFor("exposure")
                            null -> null
                        }
                    },
                    zoomLabel = zoomLabel,
                    onZoomRuler = if (zoomOn) ({ panel = null; selected = ZOOM_KEY }) else null,
                    onLensTap = { id ->
                        zoomChain.firstOrNull { it.first == id }?.let { zoom = it.second }
                        if (isVideo && state.videoCrop.digitalZoom != 1f) dispatch(SetVideoCrop(state.videoCrop.copy(digitalZoom = 1f)))
                    },
                    buttons = lensSet,
                    mmLabels = LocalCaptureExtras.current.tools.lensButtons.mmLabels,
                    zoomChain = zoomChain,
                    // Digital focal lengths: photo (and Pro) through the zoom, video through the crop; not in Pano.
                    stopsAvailable = !pano && (zoomOn || (isVideo && chainLens != null)),
                    totalZoom = if (zoomOn) zoom else null,
                    onResetPanel = { target ->
                        when (target) {
                            // Exposure: the Rawr AE options (metering, night look, balance, slowest shutter, gain,
                            // speed) back to their defaults; Rawr / Device AE stays as chosen.
                            // Presets (Nov 17): RESET goes back to P0, the baseline. Exposure: P0's Rawr AE options.
                            X14uPanel.Exposure -> presetActions.resetToBaseline(
                                setOf(com.rawr.camera.settings.model.PresetGroup.Exposure))
                            // Look: P0's profile, tone (or AgX), highlights and tone mapping; a film recipe's own values
                            // go back to the recipe (film on / off and the chosen recipe stay).
                            X14uPanel.Look -> {
                                presetActions.resetToBaseline(setOf(com.rawr.camera.settings.model.PresetGroup.Look))
                                filmQuick?.takeIf { film && it.modified }?.let { q ->
                                    q.selectedPresetId?.let { onFilmEvent(CaptureFilmEvent.SelectPreset(it)) }
                                }
                            }
                        }
                    },
                    onStop = { stop ->
                        if (isVideo) {
                            dispatch(SetVideoCrop(state.videoCrop.copy(digitalZoom = stop.crop)))
                            if (stop.lens != state.selectedLensId) dispatch(SelectLens(stop.lens))
                        } else {
                            setZoom(stop.zoom)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().alpha(recordingDim)
                        .padding(bottom = (31.dp * s - 18.dp).coerceAtLeast(4.dp), start = 7.dp, end = 7.dp)
                )
                // Preset badge just above the Look button: text only, grey P0, accent P1…, "*" once changed (only
                // where there's no room for the nameplate under the shutter row).
                if (activePreset != null && !plateBelow) {
                    X14uPresetBadge(
                        label = activePreset.label + if (presetChanged) "*" else "",
                        accent = !activePreset.isBaseline,
                        enabled = !recordingLock && !panoLocked,
                        orientation = orientation,
                        onTap = {
                            val open = !presetsOpen
                            closeAll()
                            presetsOpen = open
                        },
                        modifier = Modifier.align(Alignment.BottomStart)
                            .alpha(recordingDim)
                            // With the strip open the lens row hides and the badge takes the Look button's place.
                            .padding(start = 7.dp, bottom = (31.dp * s - 18.dp).coerceAtLeast(4.dp) +
                                if (presetsShown) 12.dp else 42.dp)
                    )
                }
            }
            CaptureTopBar(
                state, dispatch, onOpenSettings, videoLocked = recordingLock, showLog = false,
                modifier = Modifier.padding(top = cutoutTop).alpha(recordingDim),
                transparent = isVideo,
                // A tall video shape (2.39, XPan) starts under the bar: its top corner marks would sit on the crop chip
                // and DCG, so the bar's items step in past them (22 dp marks + a little), as the slot does at the foot.
                sideInset = if (isVideo && videoTop < cutoutTop + CaptureDimens.TopBarHeight)
                    ((layoutWidth - videoRect.width) / 2 + 22.dp + 4.dp).coerceAtLeast(0.dp) else 0.dp,
                toggles = { X14uOptionToggles(quick, lensOptions, isVideo, enabled = !recordingLock, orientation, onQuickEvent, burst || pano) },
                toggleCount = x14uOptionToggleCount(quick, lensOptions, isVideo, burst || pano),
                toolsOpen = toolsOpen,
                onToggleTools = { openTools(!toolsOpen) },
                onVideoCrop = {
                    val open = !cropOpen
                    closeAll()
                    cropOpen = open
                },
                burstFps = if (burst) burstFps else null,
                onBurstFps = com.rawr.camera.burst.BurstSettings::cycleFps,
                burstPre = if (burst) burstPre else null,
                burstPreFill = burstPreFill,
                onBurstPre = com.rawr.camera.burst.BurstSettings::cyclePre
            )
            val buttonTestOn by com.rawr.camera.integration.ButtonTest.enabled.collectAsState()
            if (buttonTestOn) {
                X14uButtonTestOverlay(
                    Modifier.zIndex(30f).align(Alignment.TopCenter).offset(y = top + 6.dp).padding(horizontal = 12.dp).fillMaxWidth()
                )
            }
            if (timedOpen && !isVideo) {
                val landscape = orientation == Orientation.Landscape
                X14uTimedBurstPanel(
                    values = timedSettings,
                    onChange = com.rawr.camera.burst.TimedBurstSettings::update,
                    onClose = { timedOpen = false },
                    modifier = if (landscape) Modifier.zIndex(30f).align(Alignment.Center).uprightLayoutInLandscape(orientation)
                        .width(minOf(fullHeight - top * 2, 560.dp))
                    else Modifier.zIndex(30f).align(Alignment.TopCenter).offset(y = top + 6.dp).padding(horizontal = 12.dp).fillMaxWidth()
                )
            }
            if (cropOpen && isVideo) {
                // Landscape: the whole panel turns, centred on the screen.
                val landscape = orientation == Orientation.Landscape
                X14uVideoCropPanel(
                    crop = state.videoCrop,
                    onChange = { dispatch(SetVideoCrop(it)) },
                    onClose = { cropOpen = false },
                    wide = landscape,
                    // Over everything else on the screen (value row, lens pill, mode names).
                    modifier = if (landscape) Modifier.zIndex(30f).align(Alignment.Center).uprightLayoutInLandscape(orientation)
                        .width(minOf(fullHeight - top * 2, 560.dp))
                    else Modifier.zIndex(30f).align(Alignment.TopCenter).offset(y = top + 6.dp).padding(horizontal = 12.dp).fillMaxWidth()
                )
            }
            presetSheet?.let { sheet ->
                // Like the video crop panel: landscape turns the whole sheet, centred on the screen.
                val sheetModifier = if (orientation == Orientation.Landscape) Modifier.zIndex(30f).align(Alignment.Center)
                    .uprightLayoutInLandscape(orientation).width(minOf(fullHeight - top * 2, 560.dp))
                else Modifier.zIndex(30f).align(Alignment.TopCenter).offset(y = top + 6.dp).padding(horizontal = 12.dp).fillMaxWidth()
                if (sheet == PRESET_SAVE) {
                    X14uPresetSaveSheet(
                        label = "P${presetsUi.items.size}",
                        current = presetCameraNow(),
                        summary = presetsUi.current,
                        onSave = { name, groups ->
                            presetActions.save(name, groups, presetCameraNow())
                            presetSheet = null
                        },
                        onClose = { presetSheet = null },
                        modifier = sheetModifier
                    )
                } else {
                    val item = presetsUi.items.firstOrNull { it.id == sheet }
                    if (item == null) presetSheet = null
                    else X14uPresetDetailsSheet(
                        item = item,
                        active = item.id == presetsUi.activeId,
                        full = presetsUi.full,
                        onUpdate = {
                            presetActions.update(item.id, presetCameraNow())
                            presetSheet = null
                        },
                        onRename = { presetActions.rename(item.id, it) },
                        onDuplicate = { presetActions.duplicate(item.id) },
                        onDelete = {
                            presetActions.delete(item.id)
                            presetSheet = null
                        },
                        onRestoreDefaults = { presetActions.restoreBaseline() },
                        onClose = { presetSheet = null },
                        modifier = sheetModifier
                    )
                }
            }
            filmSheet?.let { sheet ->
                val sheetModifier = if (orientation == Orientation.Landscape) Modifier.zIndex(30f).align(Alignment.Center)
                    .uprightLayoutInLandscape(orientation).width(minOf(fullHeight - top * 2, 560.dp))
                else Modifier.zIndex(30f).align(Alignment.TopCenter).offset(y = top + 6.dp).padding(horizontal = 12.dp).fillMaxWidth()
                if (sheet == FILM_SAVE) {
                    X14uFilmRecipeSaveSheet(
                        label = "F${filmRecipes.items.size + 1}",
                        onSave = { name ->
                            filmRecipeActions.save(name)
                            filmSheet = null
                            if (filmEdit != null) {
                                filmRecipeActions.finishEdit()
                                filmEdit = null
                                selected = null
                                filmOpen = true
                            }
                        },
                        onClose = { filmSheet = null },
                        modifier = sheetModifier
                    )
                } else {
                    val item = filmRecipes.items.firstOrNull { it.id == sheet }
                    if (item == null) filmSheet = null
                    else X14uFilmRecipeDetailsSheet(
                        item = item,
                        onEdit = {
                            filmSheet = null
                            filmRecipeActions.beginEdit(item.id)
                            filmOpen = false
                            selected = null
                            filmEdit = item.id
                        },
                        onRename = { filmRecipeActions.rename(item.id, it) },
                        onDuplicate = {
                            filmRecipeActions.duplicate(item.id)
                            filmSheet = null
                        },
                        onDelete = {
                            filmRecipeActions.delete(item.id)
                            filmSheet = null
                        },
                        onClose = { filmSheet = null },
                        modifier = sheetModifier
                    )
                }
            }
            val onCellTap: (String) -> Unit = { key ->
                presetsOpen = false
                presetSheet = null
                filmOpen = false
                filmSheet = null
                if (panoLocked) Unit
                else if (panel == null) selected = if (selected == key) null else key
                else {
                    panelKey = key
                    panelMemory.remember(if (panel == X14uPanel.Look) "look.$lookTab" else "exposure", key)
                }
            }
            // Classic video: the readouts dim while recording, so the picture stands out (the shutter row stays bright).
            val rowModifier = Modifier.fillMaxWidth().offset(y = rowY).height(rowHeight)
            if (panel == null && filmEditing && !presetsShown) {
                val editing = filmRecipes.items.firstOrNull { it.id == filmEdit }
                X14uFilmEditHeader(
                    title = editing?.let { "${it.label} · ${it.name}" } ?: "New recipe",
                    saveLabel = if (editing != null) "Update" else "Save",
                    onCancel = {
                        filmRecipeActions.cancelEdit()
                        filmEdit = null
                        selected = null
                        filmOpen = true
                    },
                    onSave = {
                        if (editing != null) {
                            filmRecipeActions.update(editing.id)
                            filmRecipeActions.finishEdit()
                            filmEdit = null
                            selected = null
                            filmOpen = true
                        } else filmSheet = FILM_SAVE
                    },
                    modifier = Modifier.fillMaxWidth().offset(y = rowY - 40.dp).height(36.dp)
                )
            }
            if (presetsShown && filmStripOpen) {
                Box(Modifier.fillMaxWidth().offset(y = rowY).height(slotY + slotHeight - rowY)) {
                    X14uFilmRecipeStrip(
                        recipes = filmRecipes,
                        enabled = true,
                        onApply = { filmRecipeActions.apply(it.id) },
                        onDetails = { filmSheet = it.id },
                        onSaveNew = {
                            // + opens the recipe editor from the live film settings (Save names it).
                            filmRecipeActions.beginEdit(null)
                            filmOpen = false
                            selected = null
                            filmEdit = FILM_NEW
                        },
                        onUpdate = { filmRecipes.activeId?.let(filmRecipeActions::update) },
                        onRevert = { filmRecipeActions.revert() },
                        onMove = filmRecipeActions::move
                    )
                }
            } else if (presetsShown) {
                Box(Modifier.fillMaxWidth().offset(y = rowY).height(slotY + slotHeight - rowY)) {
                    X14uPresetStrip(
                        presets = presetsUi,
                        changed = presetChanged,
                        enabled = true,
                        onApply = applyPreset,
                        onDetails = { presetSheet = it.id },
                        onSaveNew = { presetSheet = PRESET_SAVE },
                        onUpdate = { activePreset?.let { presetActions.update(it.id, presetCameraNow()) } },
                        onRevert = { activePreset?.let(applyPreset) },
                        onMove = presetActions::move
                    )
                }
            } else if (panel == null && filmEditing) {
                X14uCellRow(cells = cells, selectedKey = currentKey, orientation = orientation, modifier = rowModifier,
                    upright = true, onTap = { selected = it })
            } else if (panoStripShown) {
                // Pano set: the panorama forming takes the readout row and the modes (both locked meanwhile).
                X14uPanoStrip(panoSet, orientation, Modifier.fillMaxWidth().offset(y = rowY).height(slotY + slotHeight - rowY))
            } else if (panel == null && simplePhoto) {
                // Shutter and ISO the camera picks (both in A, ISO in S, shutter in I) show dim in their places; tapping
                // one switches to S or I with its editor open.
                val live = exposureCells(state, quick, lensOptions, fiftyMp)
                    .filter { (it.key == "ss" || it.key == "iso") && cells.none { c -> c.key == it.key } }
                X14uPhotoRow(cells, live, currentKey, state, dispatch, enabled = !recordingLock && !panoLocked, modifier = rowModifier,
                    onTap = onCellTap) { key ->
                    val mode = if (key == "ss") ExposureMode.ShutterPriority else ExposureMode.IsoPriority
                    if (mode in state.capabilities.supportedExposureModes) {
                        dispatch(SetExposureMode(mode))
                        selected = key
                    }
                }
            } else {
                X14uCellRow(cells = cells, selectedKey = currentKey, orientation = orientation, modifier = rowModifier,
                    upright = panel == null, onTap = onCellTap)
            }
            Box(
                Modifier.fillMaxWidth().offset(y = slotY).height(slotHeight).padding(horizontal = slotInset),
                contentAlignment = Alignment.Center
            ) {
                val key = currentKey
                if (burst && state.burst.running) {
                    // Burst: the count and speed above the finger (the shutter is under it).
                    X14uBurstCounter(state.burst, orientation)
                } else if (timedRun.running) {
                    X14uTimedCounter(timedRun, timedNow, orientation)
                } else if (panoStripShown) {
                    Unit
                } else if (key != null && !presetsShown) {
                    X14uEditorView(editorFor(key), orientation)
                } else if (panel == null && !presetsShown) {
                    // The diagnostics line (tap on the timer) stays bright while the mode names dim.
                    Box(Modifier.fillMaxSize().alpha(if (videoControls.diagnostics && showNumbers) 1f else recordingDim),
                        contentAlignment = Alignment.Center) {
                        X14uIdleControlArea(state, dispatch, videoControls, showNumbers, enabled = !recordingLock && !panoLocked)
                    }
                }
            }
            X14uShutterRow(
                state, dispatch, quick, latestImageUri, onOpenLatestImage, onOpenRenderer, onOpenFilmSimSettings,
                onOpenMultiframeSettings, onOpenLensSettings, onQuickEvent, videoControls, onToggleVideoRecording,
                enabled = !recordingLock,
                modifier = Modifier.fillMaxWidth().offset(y = shutterCenter - 46.dp).height(92.dp),
                pano = if (!pano) null else PanoShutter(panoShoot, if (panoSet.frames > 0) panoUndo else null, panoLocked),
                burst = burst,
                timed = if (timedReady || timedRun.running) TimedShutter(timedPress, timedRun.running) else null,
                videoHold = if (videoLockSettings.enabled) VideoHold(videoLockSettings.holdMs, onVideoHoldLock) {} else null,
                sideAlpha = recordingDim
            )
            if (activePreset != null && plateBelow) {
                Row(
                    Modifier.fillMaxWidth().offset(y = plateCenter - 13.dp).height(26.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    X14uPresetPlate(
                        label = activePreset.label + if (presetChanged) "*" else "",
                        name = activePreset.name,
                        accent = !activePreset.isBaseline,
                        enabled = !recordingLock && !panoLocked,
                        orientation = orientation,
                        onTap = {
                            val open = !presetsOpen
                            closeAll()
                            presetsOpen = open
                        },
                        modifier = Modifier.alpha(recordingDim)
                    )
                    // FILM plate (Nov 26): the active recipe; dim and inert with film off or in video.
                    val activeRecipe = filmRecipes.active.takeIf { film }
                    X14uPresetPlate(
                        label = activeRecipe?.label?.plus(if (filmRecipes.modified) "*" else "") ?: "F",
                        name = when {
                            !film -> "Film off"
                            activeRecipe != null -> activeRecipe.name
                            else -> "Custom"
                        },
                        accent = activeRecipe != null,
                        enabled = film && !recordingLock && !panoLocked,
                        orientation = orientation,
                        onTap = {
                            val open = !filmOpen
                            closeAll()
                            filmOpen = open
                        },
                        modifier = Modifier.alpha(recordingDim * if (film) 1f else .35f),
                        tag = "x14u_film_badge",
                        description = "Film recipe"
                    )
                }
            }
            // Tools panel, stock size and place: full width less 10 dp a side, its bottom 67 dp above the shutter's
            // centre (over the exposure row and mode strip, within thumb reach). Above everything else.
            Box(
                Modifier.fillMaxWidth().height(shutterCenter - 67.dp * s).padding(horizontal = 10.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = toolsOpen,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val lensName = state.capabilities.lenses.firstOrNull { it.id == state.selectedLensId }?.displayName.orEmpty()
                    X14uToolsPanel(
                        state = state,
                        dispatch = dispatch,
                        video = isVideo,
                        frontLens = isFrontLens(state.selectedLensId, lensName),
                        choice = toolsChoice,
                        onChoice = { toolsChoice = it },
                        onOpenSettings = { openTools(false); onOpenSettings() },
                        onClose = { openTools(false) },
                        modifier = Modifier.fillMaxWidth(),
                        tileHeight = 86.dp * s,
                        dcp = quick.dcps.firstOrNull { it.lensId == state.selectedLensId },
                        onSelectDcp = { file -> onQuickEvent(CaptureQuickEvent.SelectDcp(state.selectedLensId, file)) },
                        panoSet = if (pano) panoSet else null,
                        timedBurst = if (!isVideo && !pano && !burst) timedSettings else null,
                        onTimedBurst = {
                            openTools(false)
                            timedOpen = true
                        }
                    )
                }
            }
        }
    }
}

private fun lookTabs(film: Boolean): List<String> = buildList {
    add("PROFILE")
    add("TONE")
    add("HL")
    if (film) add("FILM")
}

@Composable
private fun FrameBrackets(modifier: Modifier) {
    Canvas(modifier) {
        val l = 22.dp.toPx()
        val w = 1.5.dp.toPx()
        val c = Color.White.copy(alpha = .55f)
        val corners = listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f))
        corners.forEach { (p, d) ->
            val o = Offset(p.x + d.x * w / 2, p.y + d.y * w / 2)
            drawLine(c, o, Offset(o.x + d.x * l, o.y), w)
            drawLine(c, o, Offset(o.x, o.y + d.y * l), w)
        }
    }
}

// ---- Top bar toggles -------------------------------------------------------------------------------------------

/** How many top-bar slots [X14uOptionToggles] fills (video shows only the lens's available toggles). */
/** How many toggles [X14uOptionToggles] shows (the top bar pads the other side to keep the chevron centred). */
private fun x14uOptionToggleCount(
    quick: ViewfinderQuickState, options: LensOptionsQuickState?, video: Boolean, burst: Boolean = false
): Int =
    lensOptionSlots(quick.lensOptions).filter { it.second.toggle }.count { (name, _) ->
        val row = options?.options?.indexOfFirst { it.name.trim().equals(name, ignoreCase = true) } ?: -1
        // The same rules as X14uOptionToggles: video keeps only rows the lens has; video, Burst and Pano hide 50 MP.
        val runsAsLens = row >= 0 && options!!.options[row].choices.any { !it.runAs.isNullOrBlank() }
        val available = options != null && row >= 0 && row !in options.unavailable && !((burst || video) && runsAsLens)
        !((video && !available) || (burst && runsAsLens))
    }

/** DCG and 50 MP (every on/off option row) as plain text: accent when on, struck through when off or absent. */
@Composable
private fun RowScope.X14uOptionToggles(
    quick: ViewfinderQuickState,
    options: LensOptionsQuickState?,
    video: Boolean,
    enabled: Boolean,
    orientation: Orientation,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    /** Burst / Pano shoot 12 MP frames: a row that runs the shot as another lens (50 MP) is hidden there, as in video. */
    burst: Boolean = false
) {
    val haptics = LocalCaptureHaptics.current
    val classic = isClassicStyle()
    lensOptionSlots(quick.lensOptions).filter { it.second.toggle }.forEach { (name, _) ->
        val row = options?.options?.indexOfFirst { it.name.trim().equals(name, ignoreCase = true) } ?: -1
        val runsAsLens = row >= 0 && options!!.options[row].choices.any { !it.runAs.isNullOrBlank() }
        // 50 MP runs at f/2 only: with another aperture chosen the chip is greyed, and a tap turns 50 MP on at f/2
        // (its choice locks Aperture to f/2; the chosen aperture comes back when 50 MP goes off).
        val apertureRow = apertureRow(options)
        val otherAperture = runsAsLens && apertureRow >= 0 &&
            options!!.options[apertureRow].choices.getOrNull(options.picks.getOrElse(apertureRow) { 0 })
                ?.label?.trim()?.equals("f/2", ignoreCase = true) == false
        val available = options != null && row >= 0 && row !in options.unavailable && !((burst || video) && runsAsLens)
        // Video, Burst and Pano shoot 12 MP frames: no 50 MP chip there.
        if ((video && !available) || (burst && runsAsLens)) return@forEach
        val on = available && options!!.picks.getOrElse(row) { 0 } == 1
        TopBarSlot {
            val color = when {
                on -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)
                available && enabled && !otherAperture -> if (classic) ClassicColors.Copper.copy(alpha = .75f) else Color.White.copy(alpha = .55f)
                else -> Color.White.copy(alpha = .22f)
            }
            Text(
                name.uppercase().replace(" ", ""),
                fontFamily = X14uFont,
                fontWeight = FontWeight.Medium,
                fontSize = dpText(13f),
                maxLines = 1,
                color = color,
                modifier = Modifier
                    .captureClickable(shape = RoundedCornerShape(8.dp), enabled = available && enabled, role = Role.Switch) {
                        haptics.selection()
                        onQuickEvent(CaptureQuickEvent.SelectLensOption(options!!.lensId, row, if (on) 0 else 1))
                    }
                    .semantics { contentDescription = "$name ${if (on) "on" else "off"}" }
                    .padding(horizontal = 6.dp, vertical = 10.dp)
                    // Off is just greyed (no strike line).
                    .uprightInLandscape(orientation)
            )
        }
    }
}

// ---- Lens row --------------------------------------------------------------------------------------------------

/** Classic: what a round button is drawn as (a metal knob, the Look button's colour wheel, the knurled exposure dial). */
private enum class ClassicFace { Knob, HueWheel, Dial }

/** Round dark button: accent icon and ring when [highlighted] (lens overrides), filled accent when [open]. */
@Composable
private fun X14uRoundButton(
    icon: ImageVector,
    description: String,
    orientation: Orientation,
    open: Boolean = false,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    iconScale: Float = .55f,
    onLongClick: (() -> Unit)? = null,
    /** Turn the icon upright in landscape (shutter row); the lens row's round buttons don't. */
    turn: Boolean = true,
    face: ClassicFace = ClassicFace.Knob,
    /** Classic: this word engraved on the knob instead of the icon (Nov 18: Classic buttons are text, the camera switch alone an icon). */
    classicLabel: String? = null,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val classic = isClassicStyle()
    // Classic: a metal knob (or the wheel / dial) one size up; open and highlighted show as an accent ring.
    val drawn = if (classic) size + 6.dp else size
    Box(
        Modifier
            .size(drawn)
            .then(
                if (!classic) Modifier.background(if (open) CaptureColors.Accent else Color.Black.copy(alpha = .5f), CircleShape)
                else Modifier.drawBehind {
                    val r = this.size.minDimension / 2f
                    when (face) {
                        ClassicFace.Knob -> drawMetalKnob(center, r)
                        ClassicFace.HueWheel -> drawHueWheel(center, r, dim = !enabled)
                        ClassicFace.Dial -> drawKnurledDial(center, r)
                    }
                    if (open || highlighted) drawCircle(CaptureColors.Accent, r - 1.dp.toPx(), center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(if (open) 2.dp.toPx() else 1.2.dp.toPx()))
                }
            )
            .then(if (highlighted && !open && !classic) Modifier.border(1.2.dp, CaptureColors.Accent, CircleShape) else Modifier)
            .captureCombinedClickable(
                shape = CircleShape,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick?.let { action -> { haptics.longPress(); action() } }
            ) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        // Classic: the colour wheel is the Look button's face (no icon); the dial keeps a small one.
        if (classic && classicLabel != null) Text(
            classicLabel,
            color = if (enabled) ClassicColors.KnobText.copy(alpha = if (ClassicColors.bronze) .95f else .75f) else Color.White.copy(alpha = .25f),
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = .3.sp,
            maxLines = 1,
            modifier = if (turn) Modifier.uprightInLandscape(orientation) else Modifier
        ) else if (!classic || face != ClassicFace.HueWheel) Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Color.White.copy(alpha = .25f)
                open && !classic -> Color.Black
                open || highlighted -> CaptureColors.Accent
                classic -> ClassicColors.KnobText.copy(alpha = .9f)
                else -> Color.White.copy(alpha = .9f)
            },
            modifier = Modifier.size(size * (if (classic && face == ClassicFace.Dial) iconScale * .7f else iconScale))
                .then(if (turn) Modifier.uprightInLandscape(orientation) else Modifier)
        )
    }
}

@Composable
private fun X14uLensRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    panel: X14uPanel?,
    lookTabs: List<String>,
    lookTab: String,
    onLookTab: (String) -> Unit,
    enabled: Boolean,
    onTogglePanel: (X14uPanel) -> Unit,
    modifier: Modifier,
    /** Digital zoom: the live value shown on the lens in use (e.g. "2.6x"); null = its own name. */
    zoomLabel: String? = null,
    /** Long-press on any lens: the zoom ruler (null where there is no digital zoom). */
    onZoomRuler: (() -> Unit)? = null,
    /** Tap on a lens: its own zoom (crop 1). */
    onLensTap: (String) -> Unit = {},
    /** This mode's lens set (hidden lenses, digital focal lengths). */
    buttons: com.rawr.camera.settings.model.LensButtonSet = com.rawr.camera.settings.model.LensButtonSet(),
    /** Labels as focal lengths (number over "mm"). */
    mmLabels: Boolean = false,
    zoomChain: List<Pair<String, Float>> = emptyList(),
    stopsAvailable: Boolean = false,
    /** Photo: the total zoom in use (chain units); null in video. */
    totalZoom: Float? = null,
    onStop: (com.rawr.camera.settings.model.ResolvedStop) -> Unit = {},
    /** RESET in the Look / Exposure pill: that panel's processing options back to their defaults. */
    onResetPanel: (X14uPanel) -> Unit = {}
) {
    val haptics = LocalCaptureHaptics.current
    val extras = LocalCaptureExtras.current
    val orientation = state.orientation
    val stops = if (!stopsAvailable) emptyList() else buttons.digital.filter { it.on }
        .mapNotNull { com.rawr.camera.settings.model.resolveDigitalStop(it.mm, zoomChain) }
    // The stop in use: photo by its total zoom, video by its lens and crop.
    val activeStop = stops.firstOrNull { stop ->
        if (totalZoom != null) kotlin.math.abs(totalZoom - stop.zoom) <= stop.zoom * .01f
        else stop.lens == state.selectedLensId && kotlin.math.abs(state.videoCrop.digitalZoom - stop.crop) <= stop.crop * .01f
    }
    // A hidden lens never shows (Nov 17: zooming past a digital focal length onto a hidden lens put that lens on the
    // pill with the zoom on it). The live focal length shows on the pill's item just below it instead (see anchor).
    val lenses = state.capabilities.lenses.filterNot { isFrontLens(it.id, it.displayName) }
        .filter { it.id !in buttons.hidden }
    val lensMm: (String) -> Int? = { id -> com.rawr.camera.settings.model.ZoomChain.factorOf(id)?.let { com.rawr.camera.settings.model.LensButtons.mmOf(it) } }
    // Lenses in their order, each digital stop before the first lens longer than it.
    val items: List<Any> = buildList {
        val pending = stops.sortedBy { it.mm }.toMutableList()
        lenses.forEach { lens ->
            val mm = lensMm(lens.id)
            if (mm != null) while (pending.isNotEmpty() && pending.first().mm < mm) add(pending.removeAt(0))
            add(lens)
        }
        addAll(pending)
    }
    val itemMm: (Any) -> Int? = { item ->
        (item as? com.rawr.camera.model.LensCapability)?.let { lensMm(it.id) } ?: (item as? com.rawr.camera.settings.model.ResolvedStop)?.mm
    }
    // The focal length in use when it isn't a pill stop: the lens's own scaled by any zoom on it.
    val liveMm: Float? = if (activeStop != null) null else {
        val own = lensMm(state.selectedLensId)?.toFloat()
        val factor = zoomChain.firstOrNull { it.first == state.selectedLensId }?.second
        if (own != null && factor != null && totalZoom != null) own * totalZoom / factor else own
    }
    // The item that stands for it: the longest one at or below it (the widest when it is wider than all), so the
    // live zoom shows on "55" between 55 and 75 mm, not on a hidden 46 mm lens.
    val anchor: Any? = liveMm?.let { live ->
        items.filter { (itemMm(it) ?: Int.MAX_VALUE) <= live + .5f }.maxByOrNull { itemMm(it) ?: 0 }
            ?: items.minByOrNull { itemMm(it) ?: Int.MAX_VALUE }
    }
    // Grip zoom lever: the next focal length up or down the pill (lenses and digital ones), as a tap would.
    val step by androidx.compose.runtime.rememberUpdatedState { direction: Int ->
        // The lens's own pill number scaled by any zoom on it (3.2x x 23 = 73.6 would miss the "75" button and
        // step onto it again, so the lever stopped at 75 mm).
        val ownMm = lensMm(state.selectedLensId)?.toFloat()
        val ownFactor = zoomChain.firstOrNull { it.first == state.selectedLensId }?.second
        val currentMm = activeStop?.mm?.toFloat()
            ?: if (totalZoom != null && ownMm != null && ownFactor != null) ownMm * totalZoom / ownFactor
            else ownMm ?: totalZoom?.let { it * com.rawr.camera.settings.model.LensButtons.MAIN_MM }
        if (enabled && currentMm != null) {
            val candidates = items.mapNotNull { item -> itemMm(item)?.let { item to it } }
            val next = if (direction > 0) candidates.filter { it.second > currentMm + .5f }.minByOrNull { it.second }
                else candidates.filter { it.second < currentMm - .5f }.maxByOrNull { it.second }
            when (val item = next?.first) {
                is com.rawr.camera.model.LensCapability -> {
                    haptics.selection()
                    onLensTap(item.id)
                    if (state.selectedLensId != item.id) dispatch(SelectLens(item.id))
                }
                is com.rawr.camera.settings.model.ResolvedStop -> {
                    haptics.selection()
                    onStop(item)
                }
                else -> Unit
            }
        }
    }
    LaunchedEffect(Unit) { com.rawr.camera.integration.GripEvents.lensSteps.collect { step(it) } }
    val groups = quick.overrideGroups
    Box(modifier.height(40.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) {
            X14uRoundButton(
                X14uIcons.Look, "Look: profile, tone and highlights", orientation,
                open = panel == X14uPanel.Look,
                highlighted = groups.any { it != LensOverrides.GROUP_AE },
                turn = false,
                face = ClassicFace.HueWheel
            ) { onTogglePanel(X14uPanel.Look) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            X14uRoundButton(
                Icons.Outlined.Exposure, "Exposure: auto exposure and metering. Long press for exposure settings.", orientation,
                open = panel == X14uPanel.Exposure,
                highlighted = LensOverrides.GROUP_AE in groups,
                onLongClick = extras.onOpenExposureSettings,
                turn = false,
                face = ClassicFace.Dial
            ) { onTogglePanel(X14uPanel.Exposure) }
        }
        val pill = RoundedCornerShape(18.dp)
        val classic = isClassicStyle()
        // Classic (user, Nov 18): lighter, see-through, a hairline edge; the focal lengths as text only.
        Box(Modifier.align(Alignment.Center).padding(horizontal = if (classic) 46.dp else 42.dp)) {
        Row(
            Modifier
                .height(36.dp)
                .background(Color.Black.copy(alpha = if (classic) .26f else .4f), pill)
                .then(if (classic) Modifier.border(.5.dp, Color.White.copy(alpha = .16f), pill) else Modifier)
                .padding(horizontal = 4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (panel) {
                X14uPanel.Look -> {
                    lookTabs.forEach { tab ->
                        val on = tab == lookTab || (tab == "PROFILE" && lookTab !in lookTabs)
                        PillItem(if (tab == "HL") "HIGHLIGHTS" else tab, on, orientation, width = null) {
                            haptics.selection()
                            onLookTab(tab)
                        }
                    }
                    PillItem("RESET", false, orientation, width = null) {
                        haptics.selection()
                        onResetPanel(X14uPanel.Look)
                    }
                }
                X14uPanel.Exposure -> {
                    PillItem(
                        if (quick.rawrAe.enabled) "RAWR AE" else "DEVICE AE", true, orientation, width = null
                    ) { onTogglePanel(X14uPanel.Exposure) }
                    PillItem("RESET", false, orientation, width = null) {
                        haptics.selection()
                        onResetPanel(X14uPanel.Exposure)
                    }
                }
                null -> items.forEach { item ->
                    val lens = item as? com.rawr.camera.model.LensCapability
                    val stop = item as? com.rawr.camera.settings.model.ResolvedStop
                    val isSelected = when {
                        activeStop != null -> stop == activeStop
                        anchor != null -> item === anchor
                        else -> lens != null && state.selectedLensId == lens.id
                    }
                    val name = lens?.displayName ?: "${stop!!.mm} mm"
                    // The anchor away from its own focal length shows the live one.
                    val live = liveMm?.takeIf { item === anchor && kotlin.math.abs(it - (itemMm(item) ?: 0)) > .5f }
                    // Label: the zoom factor ("1x", "2.2x") or the focal length over "mm".
                    val mm: Int? = when {
                        live != null -> live.roundToInt()
                        stop != null -> stop.mm
                        else -> lensMm(lens!!.id)
                    }
                    val factorText = when {
                        live != null -> zoomLabel ?: zoomText(live / com.rawr.camera.settings.model.LensButtons.MAIN_MM)
                        stop != null -> zoomText(stop.zoom)
                        else -> lens!!.displayName
                    }
                    Box(
                        Modifier
                            .size(42.dp, 36.dp)
                            .captureCombinedClickable(
                                shape = CircleShape,
                                enabled = enabled,
                                onLongClickLabel = if (onZoomRuler != null) "Zoom" else null,
                                // Any lens: the zoom ruler at the zoom in use (never settings).
                                onLongClick = onZoomRuler?.let { open -> { haptics.selection(); open() } }
                            ) {
                                haptics.selection()
                                if (lens != null) {
                                    onLensTap(lens.id)
                                    if (state.selectedLensId != lens.id) dispatch(SelectLens(lens.id))
                                } else onStop(stop!!)
                            }
                            .semantics {
                                contentDescription = "Lens $name"
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected && !classic) Box(Modifier.size(32.dp).background(Color.Black.copy(alpha = .55f), CircleShape))
                        // Classic: a short accent line under the lens in use.
                        if (isSelected && classic) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
                            .size(14.dp, 1.5.dp).background(if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)))
                        val color = when {
                            isSelected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)
                            else -> Color.White.copy(alpha = if (enabled) .9f else .3f)
                        }
                        if (mmLabels && mm != null) {
                            Column(Modifier.uprightInLandscape(orientation), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(mm.toString(), fontFamily = X14uFont,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    fontSize = dpText(13f), lineHeight = dpText(14f), maxLines = 1, color = color)
                                Text("mm", fontFamily = X14uFont, fontSize = dpText(8.5f), lineHeight = dpText(9f),
                                    maxLines = 1, color = color.copy(alpha = color.alpha * .8f))
                            }
                        } else {
                            Text(
                                factorText,
                                fontFamily = X14uFont,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                fontSize = dpText(if (isSelected) 13f else 14f),
                                maxLines = 1,
                                color = color,
                                modifier = Modifier.uprightInLandscape(orientation)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PillItem(text: String, on: Boolean, orientation: Orientation, width: Dp?, onClick: () -> Unit) {
    // Panel tabs stay as they are in landscape (user's choice).
    Text(
        text,
        fontFamily = X14uFont,
        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        fontSize = dpText(12f),
        maxLines = 1,
        color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .7f),
        style = TextStyle(
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
            )
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .captureClickable(shape = RoundedCornerShape(14.dp)) { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    )
}

// ---- Exposure row ----------------------------------------------------------------------------------------------

private fun exposureValue(parameter: ExposureParameter, state: CaptureUiState): Pair<String, Boolean> {
    if (parameter == ExposureParameter.Ev && CaptureTransitions.evAdjustsRenderExposure(state)) {
        return renderExposureLabel(state.renderExposureTenths) to (state.renderExposureTenths != 0)
    }
    val capability = state.capabilities.capabilityFor(parameter)
    val requestedId = state.requestedCandidateIdFor(parameter)
    val locked = CaptureTransitions.isParameterLocked(state, parameter)
    val applied = when (parameter) {
        ExposureParameter.Shutter -> state.exposureApplied.shutter
        ExposureParameter.Iso -> state.exposureApplied.iso
        ExposureParameter.Ev -> state.exposureApplied.evOrMeter
    }
    val requested = capability.candidate(requestedId)?.displayLabel ?: "—"
    val isEv = parameter == ExposureParameter.Ev
    val isManualMeter = isEv && state.exposureControl.mode == ExposureMode.Manual
    val videoShutter = parameter == ExposureParameter.Shutter && state.captureMode == CaptureMode.Video
    val value = when {
        isManualMeter -> applied?.displayLabel ?: "—"
        locked || isEv -> requested
        videoShutter -> applied?.sourceCandidateId?.let { capability.candidate(it)?.displayLabel } ?: requested
        else -> applied?.displayLabel ?: requested
    }
    val manual = if (isEv) requestedId != capability.zeroEvCandidateId() else locked
    return value to manual
}

/** The aperture row of a lens, if it has one (every choice an f-number). */
private fun apertureRow(options: LensOptionsQuickState?): Int {
    val list = options?.options ?: return -1
    return list.indexOfFirst { o -> !o.toggle && o.choices.isNotEmpty() && o.choices.all { it.label.trim().startsWith("f/") } }
        .takeIf { it >= 0 && it !in options.unavailable } ?: -1
}

private fun exposureCells(
    state: CaptureUiState, quick: ViewfinderQuickState, options: LensOptionsQuickState?, fiftyMp: Boolean = false
): List<X14uCell> {
    val (ev, evManual) = exposureValue(ExposureParameter.Ev, state)
    val (ss, ssManual) = exposureValue(ExposureParameter.Shutter, state)
    val (iso, isoManual) = exposureValue(ExposureParameter.Iso, state)
    val row = apertureRow(options)
    val aperture = if (row >= 0) options!!.options[row].choices.getOrNull(options.picks.getOrElse(row) { 0 })?.label.orEmpty()
    else quick.nativeApertures[state.selectedLensId]?.let { "f/" + "%.1f".format(it).removeSuffix(".0") } ?: "f/–"
    val wbManual = CaptureTransitions.isWhiteBalanceLocked(state)
    // Auto values show what the camera picked (white balance in Kelvin, focus as a distance), with an A badge.
    val af = when (state.focus.mode) {
        FocusMode.Af -> state.focusDistanceLabel().takeIf { it != "AUTO" } ?: "AF"
        FocusMode.AfLock -> "AF-L"
        FocusMode.Mf -> "MF"
    }
    val awb = state.autoWhiteBalanceTemperatureK?.let { "${(it / 10f).roundToInt() * 10}K" } ?: "AWB"
    return listOf(
        X14uCell("ev", ev, "EV", evManual),
        // 50 MP on the main lens: the shot runs at f/2 (aperture at 50 MP doesn't work yet), so the cell shows F2, greyed.
        if (fiftyMp && row >= 0) X14uCell("f", "F2", "A", enabled = false)
        else X14uCell("f", aperture.replace("f/", "F"), "A", enabled = row >= 0),
        X14uCell("ss", ss, "S", ssManual, auto = !ssManual),
        X14uCell("iso", iso, "ISO", isoManual, auto = !isoManual),
        X14uCell("wb", if (wbManual) "${state.whiteBalanceTemperatureK}K" else awb,
            // A tint away from 0 shows under the value.
            if (wbManual && state.whiteBalanceTint != 0) "WB ${if (state.whiteBalanceTint > 0) "+" else ""}${state.whiteBalanceTint}" else "WB",
            wbManual,
            enabled = state.capabilities.manualWhiteBalanceSupported || wbManual, auto = !wbManual),
        X14uCell("af", if (state.focus.mode == FocusMode.Mf) state.focusDistanceLabel() else af, "F",
            state.focus.mode != FocusMode.Af, auto = state.focus.mode == FocusMode.Af)
    )
}

@Composable
private fun X14uCellRow(
    cells: List<X14uCell>,
    selectedKey: String?,
    orientation: Orientation,
    modifier: Modifier,
    /** The exposure row turns upright in landscape (panel rows stay). */
    upright: Boolean = false,
    onTap: (String) -> Unit
) {
    // Up to 7 cells share the width (smaller text past 6: Pano's row in S / I / M holds 7 with F); more scroll.
    val scroll = cells.size > 7
    val compact = cells.size > 6
    Row(
        modifier
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEach { cell ->
            if (cell.groupStart) {
                Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 22.dp).background(Color.White.copy(alpha = .2f)))
            }
            X14uCellView(
                cell, cell.key == selectedKey, orientation, compact = compact,
                modifier = (if (scroll) Modifier.width(66.dp) else Modifier.weight(1f)).fillMaxHeight(),
                upright = upright,
                onTap = onTap
            )
        }
    }
}

@Composable
private fun X14uCellView(
    cell: X14uCell,
    isOpen: Boolean,
    orientation: Orientation,
    compact: Boolean,
    modifier: Modifier,
    upright: Boolean = false,
    onTap: (String) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .captureClickable(shape = CaptureTextRippleShape, enabled = cell.enabled) {
                haptics.selection()
                onTap(cell.key)
            }
            .semantics {
                contentDescription = "${cell.label} ${cell.value}"
                selected = isOpen
            },
        contentAlignment = Alignment.Center
    ) {
        // Panel cells stay as they are in landscape; the exposure row turns upright (user's choice). Turned, the value
        // is measured at its own length (not the cell's width, which would clip it) and runs along the row's height.
        val turned = upright && orientation == Orientation.Landscape
        Column(if (!upright) Modifier else Modifier.then(if (turned) Modifier.wrapContentSize(unbounded = true) else Modifier)
            .uprightInLandscape(orientation),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cell.value,
                fontFamily = X14uReadoutFont,
                softWrap = false,
                fontSize = dpText(if (compact) 14f else 16f),
                lineHeight = dpText(if (compact) 16f else 19f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = when {
                    isOpen -> CaptureColors.Accent
                    !cell.enabled -> Color.White.copy(alpha = .35f)
                    cell.changed -> CaptureColors.AccentSoft
                    else -> Color.White
                }
            )
            val labelColor = if (isOpen) CaptureColors.Accent else Color.White.copy(alpha = if (cell.enabled) .55f else .25f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cell.label,
                    fontFamily = X14uFont,
                    fontSize = dpText(10.5f),
                    lineHeight = dpText(12f),
                    maxLines = 1,
                    color = labelColor
                )
                if (cell.auto) {
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.size(10.dp).background(labelColor, CircleShape), contentAlignment = Alignment.Center) {
                        Text("A", fontFamily = X14uFont, fontWeight = FontWeight.Medium, fontSize = dpText(7f),
                            lineHeight = dpText(8f), maxLines = 1, color = Color.Black)
                    }
                }
            }
        }
    }
}

/** Photo mode: the A·M·S·I pill (exposure mode / priorities) at the left, that mode's cells centred, aperture at the right. */
@Composable
private fun X14uPhotoRow(
    cells: List<X14uCell>,
    live: List<X14uCell>,
    selectedKey: String?,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    enabled: Boolean,
    modifier: Modifier,
    onTap: (String) -> Unit,
    onLiveTap: (String) -> Unit
) {
    val orientation = state.orientation
    val aperture = cells.firstOrNull { it.key == "f" }
    // Fixed places (Nov 19): shutter · EV · ISO, each set by you (a cell) or picked by the camera (dim).
    Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        X14uAmsiPill(state, dispatch, enabled)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("ss", "ev", "iso").forEach { k ->
                    val cell = cells.firstOrNull { it.key == k }
                    val dim = live.firstOrNull { it.key == k }
                    if (cell != null) {
                        X14uCellView(cell, cell.key == selectedKey, orientation, compact = false,
                            modifier = Modifier.width(66.dp).fillMaxHeight(), upright = true, onTap = onTap)
                    } else if (dim != null) X14uLiveValue(dim, orientation, enabled, onLiveTap)
                }
            }
        }
        Box(Modifier.size(1.dp, 22.dp).background(Color.White.copy(alpha = .15f)))
        if (aperture != null) {
            X14uCellView(aperture, aperture.key == selectedKey, orientation, compact = false,
                modifier = Modifier.width(64.dp).fillMaxHeight(), upright = true, onTap = onTap)
        }
    }
}

/** A: a value auto exposure picked, dim and a size smaller than EV so it informs without drawing the eye. */
@Composable
private fun X14uLiveValue(cell: X14uCell, orientation: Orientation, enabled: Boolean, onTap: (String) -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .width(54.dp)
            .fillMaxHeight()
            .captureClickable(shape = CaptureTextRippleShape, enabled = enabled) {
                haptics.selection()
                onTap(cell.key)
            }
            .semantics { contentDescription = "Auto ${cell.label} ${cell.value}. Tap to set it yourself." },
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.then(if (orientation == Orientation.Landscape) Modifier.wrapContentSize(unbounded = true) else Modifier)
            .uprightInLandscape(orientation), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cell.value, fontFamily = X14uReadoutFont, fontSize = dpText(14f), lineHeight = dpText(17f), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Clip, color = Color.White.copy(alpha = .38f))
            Text(cell.label, fontFamily = X14uFont, fontSize = dpText(10f), lineHeight = dpText(12f), maxLines = 1,
                color = Color.White.copy(alpha = .25f))
        }
    }
}

/**
 * Photo's exposure modes (Nov 19): S · A · I, auto in the middle (full manual is the Pro mode). Modes the camera can't
 * run are greyed. Pano's MODE chips use the same list.
 */
private val PHOTO_EXPOSURE_MODES = listOf(
    ExposureMode.ShutterPriority to "S", ExposureMode.Auto to "A", ExposureMode.IsoPriority to "I"
)

/** S · A · I: a pill in Minimal, a drum in a window in Classic and Bronze ([X14uAmsiDrum]). */
@Composable
private fun X14uAmsiPill(state: CaptureUiState, dispatch: CaptureDispatch, enabled: Boolean) {
    if (isClassicStyle()) {
        X14uAmsiDrum(state, dispatch, enabled)
        return
    }
    val haptics = LocalCaptureHaptics.current
    val supported = state.capabilities.supportedExposureModes
    val current = state.exposureControl.mode
    Row(
        Modifier.height(40.dp)
            .background(Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PHOTO_EXPOSURE_MODES.forEach { (mode, letter) ->
            val on = mode == current
            val usable = enabled && mode in supported
            Box(
                Modifier
                    .size(32.dp, 34.dp)
                    .background(if (on) CaptureColors.Accent.copy(alpha = .22f) else Color.Transparent, RoundedCornerShape(17.dp))
                    .captureClickable(shape = RoundedCornerShape(17.dp), enabled = usable) {
                        if (!on) {
                            haptics.selection()
                            dispatch(SetExposureMode(mode))
                        }
                    }
                    .semantics {
                        contentDescription = "Exposure mode $letter"
                        role = Role.RadioButton
                        selected = on
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    letter,
                    fontFamily = X14uFont,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    fontSize = dpText(15f),
                    color = when {
                        on -> CaptureColors.Accent
                        usable -> Color.White.copy(alpha = .75f)
                        else -> Color.White.copy(alpha = .25f)
                    },
                    modifier = Modifier.uprightInLandscape(state.orientation)
                )
            }
        }
    }
}

/**
 * Classic / Bronze (Nov 19, approved mockup): S · A · I printed on a drum behind a window with knurled ends, as the
 * rulers. The mode in use sits at the index marks; its neighbours turn away toward the ends (only what fits between
 * the knurls is printed, so nothing is cut). Drag it sideways to turn it a mode at a time, or tap a letter. Modes the
 * camera can't run are greyed and skipped.
 */
@Composable
private fun X14uAmsiDrum(state: CaptureUiState, dispatch: CaptureDispatch, enabled: Boolean) {
    val haptics = LocalCaptureHaptics.current
    val measurer = rememberTextMeasurer()
    val supported = state.capabilities.supportedExposureModes
    val current = state.exposureControl.mode
    val selectedIndex = PHOTO_EXPOSURE_MODES.indexOfFirst { it.first == current }.coerceAtLeast(0)
    var drag by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val latestIndex by rememberUpdatedState(selectedIndex)
    val supportedNow by rememberUpdatedState(supported)
    val orientation = state.orientation
    val knurl = 14.dp
    val lit = dpText(17f)
    val plain = dpText(14f)
    // The next usable mode from [from] in [direction], or null at the end.
    fun next(from: Int, direction: Int): Int? {
        var i = from + direction
        while (i in PHOTO_EXPOSURE_MODES.indices) {
            if (PHOTO_EXPOSURE_MODES[i].first in supportedNow) return i
            i += direction
        }
        return null
    }
    val choose: (Int) -> Unit = { i ->
        val mode = PHOTO_EXPOSURE_MODES[i].first
        if (mode != state.exposureControl.mode && mode in supportedNow) {
            haptics.detent()
            dispatch(SetExposureMode(mode))
        }
    }
    val chooseNow by rememberUpdatedState(choose)
    Box(
        Modifier
            .size(114.dp, 44.dp)
            .classicWindow(knurlWidth = knurl)
            .semantics {
                contentDescription = "Exposure mode ${PHOTO_EXPOSURE_MODES[selectedIndex].second}. Drag or tap a letter to change."
            }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { tap ->
                        // A tap on a letter left or right of the index chooses it (one step that way).
                        val dx = tap.x - size.width / 2f
                        if (abs(dx) > 12.dp.toPx()) next(latestIndex, if (dx > 0) 1 else -1)?.let(chooseNow)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val step = 30.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; drag = 0f },
                        onDragEnd = { dragging = false; drag = 0f },
                        onDragCancel = { dragging = false; drag = 0f }
                    ) { change, delta ->
                        change.consume()
                        drag += delta
                        // Dragging left turns the drum toward the letter on the right, as the rulers.
                        if (abs(drag) >= step * .6f) {
                            val direction = if (drag < 0) 1 else -1
                            val target = next(latestIndex, direction)
                            if (target != null) {
                                chooseNow(target)
                                drag += direction * step
                            } else drag = drag.coerceIn(-step * .6f, step * .6f)
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Printable half-width: inside the 3 dp rim and the knurls, with a margin; the drum turns marks away up
            // to 1.25 rad, as the ruler's drum.
            val half = cx - (3.dp + knurl + 4.dp).toPx()
            val radius = half / kotlin.math.sin(1.25f)
            val spacing = 30.dp.toPx()
            val offset = if (dragging) drag.coerceIn(-spacing, spacing) else 0f
            PHOTO_EXPOSURE_MODES.forEachIndexed { i, (mode, letter) ->
                val along = (i - selectedIndex) * spacing + offset
                val angle = along / radius
                if (abs(angle) > 1.25f) return@forEachIndexed
                val x = cx + radius * kotlin.math.sin(angle)
                val depth = kotlin.math.cos(angle)
                val on = i == selectedIndex
                val usable = enabled && mode in supported
                val style = TextStyle(
                    fontFamily = X14uFont,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    fontSize = (if (on) lit else plain) * (.7f + .3f * depth)
                )
                val layout = measurer.measure(letter, style)
                val w = layout.size.width / 2f
                if (abs(x - cx) + w > half) return@forEachIndexed
                val color = when {
                    on && !dragging -> CaptureColors.Accent
                    usable -> ClassicColors.Engraved
                    else -> Color.White.copy(alpha = .3f)
                }
                val turn = if (orientation == Orientation.Landscape) 90f else 0f
                rotate(turn, Offset(x, cy)) {
                    drawText(layout, color = color.copy(alpha = color.alpha * depth.coerceIn(.3f, 1f)),
                        topLeft = Offset(x - w, cy - layout.size.height / 2f))
                }
            }
            // Index marks at the centre, top and bottom (the accent, as the ruler's needle).
            val mark = 5.dp.toPx()
            val inset = 5.dp.toPx()
            drawLine(CaptureColors.Accent, Offset(cx, inset), Offset(cx, inset + mark), 1.5.dp.toPx())
            drawLine(CaptureColors.Accent, Offset(cx, size.height - inset - mark), Offset(cx, size.height - inset), 1.5.dp.toPx())
        }
    }
}

// ---- Editors ---------------------------------------------------------------------------------------------------

private fun numberOf(id: String): Double? = id.substringAfter(':', "").toDoubleOrNull()

/** Standard 1/3-stop ISO values. */
private val THIRD_STOP_ISO = listOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
    4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400, 204800,
    409600
)

/**
 * The ruler's stops for a camera axis, as candidate indexes: shutter on its 1/3 stops, ISO on the standard 1/3-stop
 * values (nearest camera value each), EV and video shutter angles as they are.
 */
private fun rulerStops(parameter: ExposureParameter, capability: DiscreteExposureCapability): List<Int> {
    val candidates = capability.candidates
    return when (parameter) {
        ExposureParameter.Shutter -> {
            val thirds = candidates.indices.filter { candidates[it].id in capability.magneticSnapAnchorIds }
            thirds.ifEmpty { candidates.indices.toList() }
        }
        ExposureParameter.Iso -> {
            val values = candidates.map { numberOf(it.id) }
            if (values.any { it == null }) return candidates.indices.toList()
            val min = values.first()!!
            val max = values.last()!!
            val wanted = (listOf(min) + THIRD_STOP_ISO.map { it.toDouble() }.filter { it > min * 1.05 && it < max / 1.05 } + listOf(max))
            wanted.map { w -> values.indices.minBy { abs(ln(values[it]!! / w)) } }.distinct()
        }
        ExposureParameter.Ev -> candidates.indices.toList()
    }
}

private fun exposureEditor(
    key: String,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    options: LensOptionsQuickState?,
    recordingLock: Boolean,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    wbTint: Boolean = false,
    onToggleWbTint: () -> Unit = {},
    onMfTouch: () -> Unit = {}
): X14uEditor = when (key) {
    "ev" -> exposureRuler(ExposureParameter.Ev, state, dispatch)
    "ss" -> exposureRuler(ExposureParameter.Shutter, state, dispatch)
    "iso" -> exposureRuler(ExposureParameter.Iso, state, dispatch)
    "f" -> {
        val row = apertureRow(options)
        if (row < 0 || options == null) X14uEditor.Message("Fixed aperture on this lens")
        else {
            val option = options.options[row]
            X14uEditor.Chips(
                option.choices.map { it.label },
                options.picks.getOrElse(row) { option.selected },
                enabled = row !in options.locked && !recordingLock
            ) { i -> if (i != option.selected) onQuickEvent(CaptureQuickEvent.SelectLensOption(options.lensId, row, i)) }
        }
    }
    "wb" -> wbRuler(state, dispatch, wbTint, onToggleWbTint)
    else -> focusRuler(state, dispatch, onMfTouch)
}

private fun exposureRuler(parameter: ExposureParameter, state: CaptureUiState, dispatch: CaptureDispatch): X14uEditor {
    if (parameter == ExposureParameter.Ev && CaptureTransitions.evAdjustsRenderExposure(state)) {
        // Full Manual: EV edits the render exposure, 0.1 EV steps.
        val tenths = state.renderExposureTenths
        return X14uEditor.Ruler(
            count = 101,
            index = tenths + 50,
            valueText = renderExposureLabel(tenths),
            label = { i -> if ((i - 50) % 10 == 0) renderExposureLabel(i - 50).removeSuffix(".0") else null },
            major = { i -> (i - 50) % 5 == 0 },
            spacing = 8.dp,
            auto = tenths == 0,
            onAuto = { if (tenths != 0) dispatch(ScrubRenderExposure(-tenths)) },
            onReset = { if (tenths != 0) dispatch(ScrubRenderExposure(-tenths)) },
            onIndex = { new, old -> dispatch(ScrubRenderExposure(new - old)) }
        )
    }
    val capability = state.capabilities.capabilityFor(parameter)
    val candidates = capability.candidates
    val requestedId = state.requestedCandidateIdFor(parameter)
    val mode = state.exposureControl.mode
    val supported = state.capabilities.supportedExposureModes
    val isEv = parameter == ExposureParameter.Ev
    val locked = CaptureTransitions.isParameterLocked(state, parameter)
    val isManualMeter = isEv && mode == ExposureMode.Manual
    val interactive = !isManualMeter && (isEv || locked || CaptureTransitions.lockModeFor(parameter, mode, supported) != null)
    val applied = when (parameter) {
        ExposureParameter.Shutter -> state.exposureApplied.shutter
        ExposureParameter.Iso -> state.exposureApplied.iso
        ExposureParameter.Ev -> state.exposureApplied.evOrMeter
    }
    val seedId = if (locked || isEv) requestedId
    else applied?.sourceCandidateId?.takeIf { capability.candidate(it) != null } ?: requestedId
    val stops = rulerStops(parameter, capability)
    val seed = capability.indexOf(seedId).coerceAtLeast(0)
    val seedValue = numberOf(candidates[seed].id)
    val index = stops.indices.minByOrNull { i ->
        val v = numberOf(candidates[stops[i]].id)
        if (v != null && seedValue != null && v > 0 && seedValue > 0) abs(ln(v / seedValue)) else abs(stops[i] - seed).toDouble()
    } ?: 0
    val zeroId = if (isEv) capability.zeroEvCandidateId() else null
    val auto = if (isEv) requestedId == zeroId else !locked
    val (valueText, _) = exposureValue(parameter, state)
    val anchors = capability.fullStopAnchorIds
    val video = parameter == ExposureParameter.Shutter && state.captureMode == CaptureMode.Video
    // Video: every 4th mark (labels are wider than the 14 dp spacing), counted from 180° so it's always labelled.
    val videoLabelFrom = if (video) stops.indexOfFirst { candidates[it].id == "ssa:180" }.coerceAtLeast(0) else 0
    val labelled: (Int) -> Boolean = { i ->
        val c = candidates[stops[i]]
        when {
            video -> ((i - videoLabelFrom) % 4 + 4) % 4 == 0
            isEv -> c.id in anchors
            parameter == ExposureParameter.Iso -> numberOf(c.id)?.let { v ->
                val stopsFrom100 = ln(v / 100.0) / ln(2.0)
                abs(stopsFrom100 - stopsFrom100.roundToInt()) < .08 && stopsFrom100.roundToInt() % 2 == 0
            } == true
            else -> c.id in anchors && numberOf(c.id)?.let { ns ->
                // Every third full stop (… 1/1000, 1/125, 1/15, 1/2, 4 s …).
                val stopsFrom1s = ln(ns / 1e9) / ln(2.0)
                ((stopsFrom1s.roundToInt() % 3) + 3) % 3 == 0
            } == true
        }
    }
    val reset: () -> Unit = {
        if (isEv) {
            if (zeroId != null && requestedId != zeroId) dispatch(SetExposureCandidate(parameter, zeroId))
        } else {
            CaptureTransitions.unlockModeFor(parameter, mode, supported)?.let { dispatch(SetExposureMode(it)) }
        }
    }
    return X14uEditor.Ruler(
        count = stops.size,
        index = index,
        valueText = valueText,
        label = { i -> if (labelled(i)) candidates[stops[i]].displayLabel else null },
        major = { i -> candidates[stops[i]].id in anchors },
        spacing = when (parameter) {
            ExposureParameter.Iso -> 12.dp
            ExposureParameter.Shutter -> if (video) 14.dp else 8.5.dp
            ExposureParameter.Ev -> 10.dp
        },
        enabled = interactive,
        auto = auto,
        onAuto = reset,
        onReset = reset,
        onDragStart = {
            if (!isEv && !locked) CaptureTransitions.lockModeFor(parameter, mode, supported)?.let { dispatch(SetExposureMode(it)) }
        },
        onIndex = { new, _ -> dispatch(SetExposureCandidate(parameter, candidates[stops[new]].id)) }
    )
}

private fun wbRuler(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    tintMode: Boolean = false,
    onToggleTint: () -> Unit = {}
): X14uEditor {
    val locked = CaptureTransitions.isWhiteBalanceLocked(state)
    val temp = state.whiteBalanceTemperatureK
    val tint = state.whiteBalanceTint
    val min = WhiteBalanceMode.TEMP_MIN_K
    // A covers both: auto white balance sets temperature and tint together.
    val toAuto: () -> Unit = { if (locked) dispatch(SetWhiteBalanceMode(WhiteBalanceMode.Auto)) }
    if (tintMode) {
        val tMin = WhiteBalanceMode.TINT_MIN
        return X14uEditor.Ruler(
            count = WhiteBalanceMode.TINT_MAX - tMin + 1,
            index = (tint - tMin).coerceIn(0, WhiteBalanceMode.TINT_MAX - tMin),
            valueText = if (locked) (if (tint > 0) "+$tint" else "$tint") else "AWB",
            label = { i -> (tMin + i).takeIf { it % 25 == 0 }?.let { v -> if (v > 0) "+$v M" else if (v < 0) "$v G" else "0" } },
            major = { i -> (tMin + i) % 10 == 0 },
            spacing = 8.dp,
            enabled = state.capabilities.manualWhiteBalanceSupported,
            auto = !locked,
            onAuto = toAuto,
            // Double-tap: tint back to 0, Kelvin kept.
            onReset = { if (locked) dispatch(SetWhiteBalanceTempTint(temp, 0)) },
            trailing = "K",
            trailingActive = true,
            onTrailing = onToggleTint,
            onDragStart = { if (!locked) dispatch(LockWhiteBalance(temp, tint)) },
            onIndex = { new, _ -> dispatch(SetWhiteBalanceTempTint(temp, tMin + new)) }
        )
    }
    return X14uEditor.Ruler(
        count = (WhiteBalanceMode.TEMP_MAX_K - min) / 100 + 1,
        index = ((temp - min) / 100f).roundToInt(),
        valueText = if (locked) "${temp}K" else "AWB",
        label = { i -> (min + i * 100).takeIf { it % 2000 == 0 }?.let { "${it}K" } },
        major = { i -> (min + i * 100) % 1000 == 0 },
        spacing = 8.dp,
        enabled = state.capabilities.manualWhiteBalanceSupported,
        auto = !locked,
        onAuto = toAuto,
        // Double-tap: Kelvin back to 5200K, tint kept.
        onReset = { if (locked) dispatch(SetWhiteBalanceTempTint(WhiteBalanceMode.TEMP_DEFAULT_K, tint)) else Unit },
        trailing = "TINT",
        trailingActive = locked && tint != 0,
        onTrailing = onToggleTint,
        onDragStart = { if (!locked) dispatch(LockWhiteBalance(temp, tint)) },
        onIndex = { new, _ -> dispatch(SetWhiteBalanceTempTint(min + new * 100, tint)) }
    )
}

// MF ruler: 150 steps on a squared diopter scale (diopters = minimum x ((150 - i) / 150)^2), so far distances get most
// of the ruler: at 1 m a step is about 0.04 dpt (was 0.33 with 30 linear steps: 1 m was 3 steps from infinity).
private const val FOCUS_STEPS = 150

private fun focusLabel(diopters: Float): String {
    if (diopters <= .01f) return "∞"
    val m = 1f / diopters
    return if (m < 1f) "${(m * 100).roundToInt()}cm" else if (m < 10f) "%.1fm".format(m).replace(".0m", "m") else "${m.roundToInt()}m"
}

private const val PHOTO_FOCUS_KEY = "photo.focus"
private const val ZOOM_KEY = "zoom"

/** "2.6x", "10x". */
private fun zoomText(z: Float): String =
    if (z >= 9.95f) "${z.roundToInt()}x" else "%.1f".format(z).removeSuffix(".0").let { "${it}x" }

/** Zoom ruler: log scale from the widest lens to 10x, marks at each lens and 2x / 10x, 35 mm equivalent. */
private fun zoomRuler(zoom: Float, chain: List<Pair<String, Float>>, onZoom: (Float) -> Unit): X14uEditor {
    val lo = chain.firstOrNull()?.second ?: .5f
    val hi = com.rawr.camera.settings.model.ZoomChain.MAX_ZOOM
    val steps = 120
    val zAt: (Int) -> Float = { i -> lo * Math.pow((hi / lo).toDouble(), i / (steps - 1.0)).toFloat() }
    val indexOf: (Float) -> Int = { z ->
        (kotlin.math.ln(z / lo) / kotlin.math.ln(hi / lo) * (steps - 1)).roundToInt().coerceIn(0, steps - 1)
    }
    val marks = (chain.map { it.second } + listOf(2f, hi)).distinct()
    val markIndex = marks.associateBy { indexOf(it) }
    val lens = chain.lastOrNull { it.second <= zoom + 1e-3f }?.second ?: lo
    return X14uEditor.Ruler(
        count = steps,
        index = indexOf(zoom),
        // 35 mm equivalent: the main camera (1x) is 23 mm.
        valueText = "${zoomText(zoom)} · ${(23f * zoom).roundToInt()} mm",
        label = { i -> markIndex[i]?.let { zoomText(it) } },
        major = { i -> i in markIndex },
        spacing = 6.dp,
        onReset = { onZoom(lens) },
        onIndex = { new, _ -> onZoom(zAt(new)) }
    )
}

/** Photo: AF · AF-L · MF, with the focus ruler under them in MF. */
private fun photoFocusEditor(state: CaptureUiState, dispatch: CaptureDispatch, onMfTouch: () -> Unit = {}): X14uEditor {
    val modes = buildList {
        add(FocusMode.Af)
        add(FocusMode.AfLock)
        if (state.capabilities.manualFocus.supported) add(FocusMode.Mf)
    }
    val labels = modes.map { when (it) { FocusMode.Af -> "AF"; FocusMode.AfLock -> "AF-L"; FocusMode.Mf -> "MF" } }
    val chips = X14uEditor.Chips(labels, modes.indexOf(state.focus.mode)) { i ->
        if (modes[i] != state.focus.mode) dispatch(SetFocusMode(modes[i]))
    }
    return X14uEditor.Stacked(chips, if (state.focus.mode == FocusMode.Mf) focusRuler(state, dispatch, onMfTouch) else null)
}

private fun focusRuler(state: CaptureUiState, dispatch: CaptureDispatch, onMfTouch: () -> Unit = {}): X14uEditor {
    val capability = state.capabilities.manualFocus
    val ladder = FocusLadder.forCapability(capability)
    val minimum = capability.minimumFocusDistance
    val mode = state.focus.mode
    val seed = when {
        mode == FocusMode.Mf -> state.focus.mfNormalized
        else -> state.focus.appliedFocusDiopters?.takeIf { it.isFinite() }
            ?.let { mfNormalizedFromDiopters(it, minimum) } ?: state.focus.mfNormalized
    }
    fun far(i: Int) = (FOCUS_STEPS - i) / FOCUS_STEPS.toFloat()
    val diopters: (Int) -> Float = { i -> far(i) * far(i) * minimum }
    // The ruler index of a normalized MF value (normalized = 1 - diopters / minimum).
    fun indexOf(normalized: Float) =
        (FOCUS_STEPS * (1f - kotlin.math.sqrt((1f - normalized).coerceIn(0f, 1f)))).roundToInt().coerceIn(0, FOCUS_STEPS)
    // Label the steps nearest 10 cm … 5 m (when the lens reaches them).
    val labelled = if (minimum > 0f && capability.distanceReadoutTrustworthy) {
        listOf(.1f, .2f, .3f, .5f, 1f, 2f, 5f).filter { 1f / it <= minimum }.map { m ->
            (0..FOCUS_STEPS).minBy { abs(diopters(it) - 1f / m) }
        }.toSet()
    } else emptySet()
    val toAf: () -> Unit = { if (mode != FocusMode.Af) dispatch(SetFocusMode(FocusMode.Af)) }
    return X14uEditor.Ruler(
        count = FOCUS_STEPS + 1,
        index = indexOf(seed),
        valueText = if (mode == FocusMode.Mf) state.focusDistanceLabel() else "AF",
        label = { i -> if (i in labelled) focusLabel(diopters(i)) else null },
        major = { i -> i in labelled || i == FOCUS_STEPS },
        spacing = 6.dp,
        enabled = ladder != null,
        auto = mode == FocusMode.Af,
        onAuto = toAf,
        onReset = toAf,
        endIcons = true,
        onDragStart = {
            onMfTouch()
            if (mode != FocusMode.Mf) dispatch(SetManualFocus(seed.coerceIn(0f, 1f)))
        },
        onIndex = { new, _ ->
            onMfTouch()
            dispatch(SetManualFocus(1f - far(new) * far(new)))
        }
    )
}

private fun highlightEditor(key: String, quick: ViewfinderQuickState, onQuickEvent: (CaptureQuickEvent) -> Unit): X14uEditor {
    val hl = quick.highlight
    return if (key == "hl.method") {
        X14uEditor.Chips(listOf("Off", "Propagation", "Inpaint Opposed"), hl.mode.ordinal, enabled = !quick.locked) { i ->
            onQuickEvent(CaptureQuickEvent.SetHighlightMode(HighlightQuickMode.entries[i]))
        }
    } else {
        X14uEditor.Ruler(
            count = 61,
            index = (hl.compression / 5f).roundToInt().coerceIn(0, 60),
            valueText = hl.compression.roundToInt().toString(),
            label = { i -> if (i % 10 == 0) "${i * 5}" else null },
            major = { i -> i % 5 == 0 },
            spacing = 9.dp,
            enabled = hl.mode != HighlightQuickMode.Off && !quick.locked,
            onReset = { onQuickEvent(CaptureQuickEvent.SetHighlightCompression(hl.compressionDefault)) },
            onIndex = { new, _ -> onQuickEvent(CaptureQuickEvent.SetHighlightCompression(new * 5f)) }
        )
    }
}

private fun aeCells(quick: ViewfinderQuickState, faceExposure: Boolean): List<X14uCell> {
    val ae = quick.rawrAe
    val on = ae.enabled
    return listOf(
        X14uCell("ae", if (on) "Rawr" else "Device", "AE", changed = on),
        // Expose for faces (Nov 17, also in Settings → Capture): either AE meters the main face when one is seen.
        X14uCell("ae.faces", if (faceExposure) "On" else "Off", "FACES"),
        X14uCell("ae.metering", ae.metering.label, "METER", enabled = on),
        X14uCell("ae.night", ae.nightLook.label, "NIGHT", enabled = on),
        X14uCell("ae.balance", ae.balance.label.replace("Balanced", "Bal."), "BALANCE", enabled = on),
        X14uCell("ae.slowest", ae.maxShutter.label.removeSuffix(" (1/15)"), "SLOWEST", enabled = on),
        X14uCell("ae.gain", ae.digitalGain.label, "GAIN", enabled = on)
    )
}

private fun aeEditor(key: String, quick: ViewfinderQuickState, onQuickEvent: (CaptureQuickEvent) -> Unit): X14uEditor {
    val ae = quick.rawrAe
    val enabled = !quick.locked
    return when (key) {
        "ae.metering" -> X14uEditor.Chips(RawrMetering.entries.map { it.label }, ae.metering.ordinal, enabled) {
            onQuickEvent(CaptureQuickEvent.SetMetering(RawrMetering.entries[it]))
        }
        "ae.night" -> X14uEditor.Chips(RawrNightLook.entries.map { it.label }, ae.nightLook.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(nightLook = RawrNightLook.entries[i]) })
        }
        "ae.balance" -> X14uEditor.Chips(RawrBalance.entries.map { it.label }, ae.balance.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(balance = RawrBalance.entries[i]) })
        }
        "ae.slowest" -> X14uEditor.Chips(RawrMaxShutter.entries.map { it.label }, ae.maxShutter.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(maxShutter = RawrMaxShutter.entries[i]) })
        }
        "ae.faces" -> {
            val faces = com.rawr.camera.settings.model.FaceSettings.values.value
            X14uEditor.Chips(listOf("Off", "On"), if (faces.exposure) 1 else 0, enabled) { i ->
                com.rawr.camera.settings.model.FaceSettings.update(faces.copy(exposure = i == 1))
            }
        }
        "ae.gain" -> X14uEditor.Chips(RawrDigitalGain.entries.map { it.label }, ae.digitalGain.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(digitalGain = RawrDigitalGain.entries[i]) })
        }
        else -> X14uEditor.Chips(listOf("Device AE", "Rawr AE"), if (ae.enabled) 1 else 0, enabled) {
            if ((it == 1) != ae.enabled) onQuickEvent(CaptureQuickEvent.ToggleRawrAe)
        }
    }
}

// ---- Tone / AgX / film controls (one structure: FilmSimQuickState sections) ------------------------------------

private class ConfigActions(
    val set: (String, Float) -> Unit,
    val reset: (String) -> Unit,
    val discrete: (String, Int) -> Unit = { _, _ -> },
    val flag: (String, Boolean) -> Unit = { _, _ -> }
)

/** The regular profile tone controls in the film-strip structure. */
private fun toneStripState(state: CaptureUiState): FilmSimQuickState {
    val params = TonemapCatalog.all.associate { d ->
        val value = if (d.param == null) state.renderExposureTenths / 10f else TonemapCatalog.valueOf(state, d.param).toFloat()
        d.jsonKey to FilmSimQuickParam(
            key = d.jsonKey, shortLabel = d.shortLabel, value = value, defaultValue = d.defaultValue,
            minimum = d.minimum, maximum = d.maximum, step = d.step, decimals = d.decimals,
            displayValue = if (d.param == null) renderExposureLabel(state.renderExposureTenths)
            else formatFilmStripValue(value, d.minimum, d.decimals),
            isDefault = value == d.defaultValue
        )
    }
    val groups = TonemapCatalog.all.groupBy { it.group }.map { (group, items) ->
        FilmSimQuickSubsection(group.name, group.name, items.map { FilmStripItem.Numeric(it.jsonKey) })
    }
    return FilmSimQuickState("Tone", null, false, sections = listOf(FilmSimQuickSection("tone", "TONE", groups)), params = params)
}

private fun toneActions(state: CaptureUiState, dispatch: CaptureDispatch) = ConfigActions(
    set = { key, value ->
        val d = TonemapCatalog.all.first { it.jsonKey == key }
        if (d.param == null) dispatch(SetRenderExposureTenths(com.rawr.camera.model.renderExposureTenthsOf(value)))
        else {
            val delta = value.roundToInt() - TonemapCatalog.valueOf(state, d.param)
            if (delta != 0) dispatch(ScrubTone(d.param, delta))
        }
    },
    reset = { key ->
        val d = TonemapCatalog.all.first { it.jsonKey == key }
        if (d.param == null) dispatch(SetRenderExposureTenths(0))
        else {
            val delta = d.defaultValue.roundToInt() - TonemapCatalog.valueOf(state, d.param)
            if (delta != 0) dispatch(ScrubTone(d.param, delta))
        }
    }
)

private fun agxActions(dispatch: CaptureDispatch) = ConfigActions(
    set = { key, value -> dispatchAgx(dispatch, key, value) },
    reset = { key -> dispatchAgx(dispatch, key, null) }
)

private fun filmActions(onFilmEvent: (CaptureFilmEvent) -> Unit) = ConfigActions(
    set = { key, value ->
        onFilmEvent(CaptureFilmEvent.ScrubNumeric(com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key), value))
    },
    reset = { key ->
        onFilmEvent(CaptureFilmEvent.ResetNumeric(com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key)))
    },
    discrete = { key, index ->
        onFilmEvent(CaptureFilmEvent.ScrubDiscrete(com.rawr.camera.settings.model.FilmSimDiscreteField.valueOf(key), index))
    },
    flag = { key, enabled ->
        onFilmEvent(CaptureFilmEvent.SetFlag(com.rawr.camera.settings.model.FilmSimFlag.valueOf(key), enabled))
    }
)

private fun configCells(q: FilmSimQuickState): List<X14uCell> = buildList {
    q.sections.forEachIndexed { s, section ->
        section.items.forEachIndexed { i, item ->
            val start = i == 0 && (s > 0 || isNotEmpty())
            when (item) {
                is FilmStripItem.Numeric -> q.params[item.key]?.let {
                    add(X14uCell(item.key, it.displayValue, it.shortLabel, changed = !it.isDefault, groupStart = start))
                }
                is FilmStripItem.Discrete -> q.discretes[item.key]?.let {
                    add(X14uCell(item.key, it.displayValue, it.shortLabel, groupStart = start))
                }
                is FilmStripItem.Flag -> q.flags[item.key]?.let {
                    add(X14uCell(item.key, if (it.enabled) "On" else "Off", it.shortLabel, changed = it.enabled, groupStart = start))
                }
            }
        }
    }
}

// ---- Film settings by section (Nov 26) -------------------------------------------------------------------------
// The row holds one cell per section (FILM, DIR, PRINT, FILTERS, DIFFUSION, GRAIN, HALATION, SCANNER, OUTPUT); the
// slot holds that section's settings as chips (an effect's switch first) over the chosen setting's ruler / choices.
// While an effect is off its settings are dimmed; picking one turns the effect on.

/** Effect switches: each governs the settings after it in its section (until the next switch). */
private val FilmMasterFlags = setOf(
    "GrainEnabled", "HalationEnabled", "ScannerEnabled", "CameraDiffusionEnabled", "PrintDiffusionEnabled"
)
private const val FILM_SECTION_PREFIX = "sec:"
private val FilmSectionShortLabels = mapOf("diffusion" to "DIFFUS.", "halation" to "HALAT.", "scanner" to "SCAN")

private fun FilmStripItem.itemKey(): String = when (this) {
    is FilmStripItem.Numeric -> key
    is FilmStripItem.Discrete -> key
    is FilmStripItem.Flag -> key
}

/** The section's items, each with the switch that governs it (null: none, or it is a switch). */
private fun filmSectionItems(section: com.rawr.camera.model.FilmSimQuickSection): List<Pair<FilmStripItem, String?>> {
    var master: String? = null
    return section.items.map { item ->
        if (item is FilmStripItem.Flag && item.key in FilmMasterFlags) {
            master = item.key
            item to null
        } else item to master
    }
}

private fun filmItemValue(q: FilmSimQuickState, item: FilmStripItem): String? = when (item) {
    is FilmStripItem.Numeric -> q.params[item.key]?.displayValue
    is FilmStripItem.Discrete -> q.discretes[item.key]?.displayValue
    is FilmStripItem.Flag -> q.flags[item.key]?.let { if (it.enabled) "On" else "Off" }
}

private fun filmItemLabel(q: FilmSimQuickState, item: FilmStripItem, section: com.rawr.camera.model.FilmSimQuickSection): String {
    val short = when (item) {
        is FilmStripItem.Numeric -> q.params[item.key]?.shortLabel
        is FilmStripItem.Discrete -> q.discretes[item.key]?.shortLabel
        is FilmStripItem.Flag -> q.flags[item.key]?.shortLabel
    } ?: item.itemKey()
    // A setting named like its section (grain's amount is "GRAIN") reads as its amount; the switch keeps the name.
    val named = if (short.equals(section.label, ignoreCase = true) &&
        !(item is FilmStripItem.Flag && item.key in FilmMasterFlags)) "AMOUNT" else short
    return named.lowercase().replaceFirstChar { it.uppercase() }
}

private fun filmSectionCells(q: FilmSimQuickState): List<X14uCell> = q.sections.map { section ->
    val masters = section.items.filterIsInstance<FilmStripItem.Flag>().filter { it.key in FilmMasterFlags }
    val value = when {
        masters.isNotEmpty() -> if (masters.any { q.flags[it.key]?.enabled == true }) "On" else "Off"
        section.items.all { it is FilmStripItem.Flag } -> section.items.filter { q.flags[it.itemKey()]?.enabled == true }
            .mapNotNull { q.flags[it.itemKey()]?.shortLabel }.joinToString(" ").ifEmpty { "Off" }
        else -> section.items.firstNotNullOfOrNull { filmItemValue(q, it) } ?: "—"
    }
    val changed = section.items.any { item ->
        when (item) {
            is FilmStripItem.Numeric -> q.params[item.key]?.isDefault == false
            is FilmStripItem.Flag -> item.key in FilmMasterFlags && q.flags[item.key]?.enabled == true
            else -> false
        }
    }
    X14uCell(FILM_SECTION_PREFIX + section.key, value, FilmSectionShortLabels[section.key] ?: section.label, changed = changed)
}

/** A section's editor: its settings as chips over the chosen one's ruler / choices ([chosen] null: the first setting). */
private fun filmSectionEditor(
    key: String,
    q: FilmSimQuickState,
    actions: ConfigActions,
    chosen: String?,
    onChoose: (String) -> Unit
): X14uEditor {
    val section = q.sections.firstOrNull { FILM_SECTION_PREFIX + it.key == key } ?: return X14uEditor.Message("")
    val items = filmSectionItems(section)
    if (items.isEmpty()) return X14uEditor.Message("")
    fun isMaster(item: FilmStripItem) = item is FilmStripItem.Flag && item.key in FilmMasterFlags
    val current = chosen?.takeIf { k -> items.any { it.first.itemKey() == k && !isMaster(it.first) } }
        ?: items.firstOrNull { !isMaster(it.first) }?.first?.itemKey()
    val off = { master: String? -> master != null && q.flags[master]?.enabled != true }
    val labels = items.map { (item, _) ->
        val label = filmItemLabel(q, item, section)
        val value = filmItemValue(q, item)
        if (value == null) label else "$label $value"
    }
    val top = X14uEditor.Chips(
        labels = labels,
        selected = items.indexOfFirst { it.first.itemKey() == current },
        dim = items.indices.filter { off(items[it].second) }.toSet(),
        onPick = { i ->
            val (item, master) = items[i]
            if (isMaster(item)) {
                actions.flag(item.itemKey(), q.flags[item.itemKey()]?.enabled != true)
            } else {
                if (master != null && off(master)) actions.flag(master, true)
                onChoose(item.itemKey())
            }
        }
    )
    val below = current?.let { configEditor(it, q, actions) }
    return X14uEditor.Stacked(top, below)
}

private fun configEditor(key: String, q: FilmSimQuickState, actions: ConfigActions): X14uEditor {
    q.params[key]?.let { p ->
        val step = if (p.step > 0f) p.step else .01f
        val count = (((p.maximum - p.minimum) / step).roundToInt() + 1).coerceIn(2, 2001)
        val index = ((p.value - p.minimum) / step).roundToInt().coerceIn(0, count - 1)
        val every = when {
            count <= 21 -> 5
            count <= 61 -> 10
            count <= 201 -> 25
            else -> 100
        }
        return X14uEditor.Ruler(
            count = count,
            index = index,
            valueText = p.displayValue,
            label = { i -> if (i % every == 0) formatFilmStripValue(p.minimum + i * step, p.minimum, p.decimals) else null },
            major = { i -> i % every == 0 },
            spacing = 7.dp,
            onReset = { actions.reset(key) },
            onIndex = { new, _ -> actions.set(key, (p.minimum + new * step).coerceIn(p.minimum, p.maximum)) }
        )
    }
    q.discretes[key]?.let { d -> return X14uEditor.Chips(d.options, d.selectedIndex) { actions.discrete(key, it) } }
    q.flags[key]?.let { f -> return X14uEditor.Chips(listOf("Off", "On"), if (f.enabled) 1 else 0) { actions.flag(key, it == 1) } }
    return X14uEditor.Message("")
}

// ---- Editor views ----------------------------------------------------------------------------------------------

@Composable
private fun X14uEditorView(editor: X14uEditor, orientation: Orientation) {
    when (editor) {
        is X14uEditor.Ruler -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val classic = isClassicStyle()
            if (editor.auto != null) {
                X14uAutoButton(active = editor.auto, enabled = true, orientation = orientation, onClick = editor.onAuto)
                Spacer(Modifier.width(if (classic) 8.dp else 6.dp))
            }
            // Keyed by the scale so swapping rulers (WB Kelvin <-> tint) starts the new one fresh.
            // Classic: the ruler behind a glass window with knurled ends (the user's mockup).
            androidx.compose.runtime.key(editor.count, editor.spacing) {
                X14uRuler(editor,
                    if (classic) Modifier.weight(1f).padding(vertical = 6.dp).classicWindow() else Modifier.weight(1f))
            }
            if (editor.trailing != null) {
                Spacer(Modifier.width(6.dp))
                X14uTextRoundButton(editor.trailing, editor.trailingActive, orientation, editor.onTrailing)
            }
        }
        is X14uEditor.Chips -> X14uChoiceChips(editor.labels, editor.selected, editor.enabled, orientation, Modifier.fillMaxWidth(), editor.onPick, editor.dim)
        is X14uEditor.Message -> Text(
            editor.text, fontFamily = X14uFont, fontSize = dpText(12f), color = CaptureColors.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        is X14uEditor.Stacked -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            X14uChoiceChips(editor.top.labels, editor.top.selected, editor.top.enabled, orientation,
                Modifier.fillMaxWidth().height(32.dp), editor.top.onPick, editor.top.dim)
            if (editor.below != null) Box(Modifier.fillMaxWidth().weight(1f)) { X14uEditorView(editor.below, orientation) }
        }
    }
}

@Composable
private fun X14uTextRoundButton(text: String, active: Boolean, orientation: Orientation, onClick: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    val classic = isClassicStyle()
    Box(
        Modifier
            .size(if (classic) 44.dp else 36.dp)
            .then(
                if (classic) Modifier.drawBehind { drawMetalKnob(center, this.size.minDimension / 2f) }
                else Modifier.background(if (active) CaptureColors.Accent.copy(alpha = .22f) else Color.White.copy(alpha = .12f), CircleShape)
            )
            .captureClickable(shape = CircleShape) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, fontFamily = X14uFont, fontWeight = FontWeight.Medium, fontSize = dpText(if (text.length > 2) 9.5f else 12f),
            maxLines = 1, color = when {
                active -> CaptureColors.Accent
                classic -> ClassicColors.KnobText.copy(alpha = .9f)
                else -> Color.White.copy(alpha = .85f)
            }
        )
    }
}

@Composable
private fun X14uAutoButton(active: Boolean, enabled: Boolean, orientation: Orientation, onClick: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    val classic = isClassicStyle()
    // Classic: a metal "A" button beside the window, as in the user's mockup.
    Box(
        Modifier
            .size(if (classic) 44.dp else 36.dp)
            .then(
                if (classic) Modifier.drawBehind { drawMetalKnob(center, this.size.minDimension / 2f) }
                else Modifier.background(if (active) CaptureColors.Accent.copy(alpha = .22f) else Color.White.copy(alpha = .12f), CircleShape)
            )
            .captureClickable(shape = CircleShape, enabled = enabled) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = "Auto" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "A",
            fontFamily = X14uFont,
            fontSize = dpText(15f),
            color = when {
                active -> CaptureColors.Accent
                classic -> ClassicColors.KnobText
                else -> Color.White
            },
            modifier = Modifier.uprightInLandscape(orientation)
        )
    }
}

@Composable
private fun X14uChoiceChips(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    orientation: Orientation,
    modifier: Modifier,
    onPick: (Int) -> Unit,
    dim: Set<Int> = emptySet()
) {
    // Style C (user's choice): one dark track with the selected choice highlighted. Short sets share the width
    // evenly; long ones (profiles, presets) keep their own widths and scroll inside the track.
    val haptics = LocalCaptureHaptics.current
    val even = labels.size in 2..6 && labels.all { it.length <= 7 }
    val track = RoundedCornerShape(50)
    val classic = isClassicStyle()
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .then(if (even) Modifier.fillMaxWidth() else Modifier)
                .height(36.dp)
                // Classic: the choices sit in a dark glass track with a hairline rim.
                .background(if (classic) ClassicColors.Glass else Color(0xD91C1A17), track)
                .then(if (classic) Modifier.border(.6.dp, Color.White.copy(alpha = .1f), track) else Modifier)
                .padding(3.dp)
                .then(if (even) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val isSelected = index == selected
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        // The track stays as it is in landscape (user's choice).
                        .then(if (even) Modifier.weight(1f) else Modifier.widthIn(min = 44.dp))
                        .fillMaxHeight()
                        .then(
                            if (isSelected) Modifier.background(if (classic) Color(0xFF1E1D1C) else Color(0xFF2F2A24), shape).border(1.dp, CaptureColors.Accent, shape)
                            else Modifier
                        )
                        .captureClickable(shape = shape, enabled = enabled) {
                            haptics.selection()
                            onPick(index)
                        }
                        .semantics {
                            contentDescription = label
                            role = Role.RadioButton
                            this.selected = isSelected
                        }
                        .padding(horizontal = if (even) 4.dp else 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontFamily = X14uFont,
                        fontSize = dpText(13f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            !enabled -> Color.White.copy(alpha = .3f)
                            index in dim -> (if (isSelected) CaptureColors.Accent else Color.White).copy(alpha = .3f)
                            isSelected -> CaptureColors.Accent
                            else -> Color.White.copy(alpha = .8f)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Stock-style ruler: the current value above the needle, faded labels on some ticks, ticks fading to the edges.
 * Drag left for higher values; a fast swipe moves several steps per step of travel. Double tap resets.
 */
@Composable
private fun X14uRuler(editor: X14uEditor.Ruler, modifier: Modifier) {
    val count = editor.count
    if (count <= 0) return
    val haptics = LocalCaptureHaptics.current
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    var gestureIndex by remember { mutableStateOf(editor.index.coerceIn(0, count - 1)) }
    var residual by remember { mutableStateOf(0f) }
    val latest by rememberUpdatedState(editor)
    val shown = if (dragging) gestureIndex else editor.index.coerceIn(0, count - 1)
    val labelStyle = TextStyle(fontFamily = X14uFont, fontSize = dpText(10f))
    val valueStyle = TextStyle(fontFamily = X14uFont, fontSize = dpText(14f))
    val enabled = editor.enabled
    // Classic: the scale printed on a drum turning behind the window: marks crowd and fade toward the ends.
    val drum = isClassicStyle()
    Box(modifier.fillMaxHeight()) {
        Text(
            editor.valueText,
            fontFamily = X14uFont,
            fontSize = dpText(14f),
            // Classic: the value lit in the accent through the window, as in the mockup.
            color = when {
                !enabled -> Color.White.copy(alpha = .4f)
                isClassicStyle() -> CaptureColors.Accent
                else -> Color.White
            },
            maxLines = 1,
            // Upright in landscape too, like the ticks and labels (as stock): turned, it ran over the needle.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        haptics.selection()
                        latest.onReset()
                    })
                }
                .pointerInput(enabled, count) {
                    if (!enabled) return@pointerInput
                    var lastTime = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            gestureIndex = latest.index.coerceIn(0, count - 1)
                            residual = 0f
                            lastTime = 0L
                            latest.onDragStart()
                        },
                        onDragEnd = { dragging = false; residual = 0f },
                        onDragCancel = { dragging = false; residual = 0f }
                    ) { change, delta ->
                        change.consume()
                        val stepPx = latest.spacing.toPx()
                        val dt = if (lastTime == 0L) 16L else (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                        lastTime = change.uptimeMillis
                        // Fast swipes cover more values per pixel; slow drags move one tick at a time.
                        val speed = abs(delta) / dt / density
                        val boost = if (speed > .6f) (1f + (speed - .6f) * 2.5f).coerceAtMost(6f) else 1f
                        residual -= delta * boost
                        while (abs(residual) >= stepPx) {
                            val direction = if (residual > 0) 1 else -1
                            val next = (gestureIndex + direction).coerceIn(0, count - 1)
                            residual -= direction * stepPx
                            if (next == gestureIndex) {
                                residual = 0f
                                break
                            }
                            val previous = gestureIndex
                            gestureIndex = next
                            latest.onIndex(next, previous)
                            if (latest.major(next)) haptics.detent() else haptics.sliderTick()
                        }
                    }
                }
                .semantics { contentDescription = editor.valueText }
        ) {
            val stepPx = editor.spacing.toPx()
            val cx = size.width / 2f
            val labelY = 6.dp.toPx()
            val tickBottom = size.height - 10.dp.toPx()
            val offset = if (dragging) -residual.coerceIn(-stepPx, stepPx) else 0f
            // The drum: a mark [d] along the scale shows at R sin(d / R), up to 1.25 rad either side, between the knurled
            // ends (30 dp each inside the 3 dp rim, plus a margin): nothing printed reaches them (Nov 18).
            val drumHalf = if (drum) size.width / 2f - 37.dp.toPx() else size.width / 2f
            val drumRadius = drumHalf / kotlin.math.sin(1.25f)
            val visible = if (drum) (drumRadius * 1.25f / stepPx).toInt() + 2 else (size.width / 2f / stepPx).toInt() + 2
            // The value printed over the needle, on the labels' line: labels keep clear of its real width (Nov 19: a
            // fixed 34 dp let "3.2x" run into "3.7x · 86 mm" on the zoom ruler).
            val valueLayout = measurer.measure(editor.valueText, valueStyle)
            val valueHalf = valueLayout.size.width / 2f
            // Labels go down after the ticks, nearest the needle first; one that would touch a label already placed is
            // left out (the Classic drum crowds them toward the ends).
            val labels = ArrayList<Triple<Float, Float, androidx.compose.ui.text.TextLayoutResult>>()
            for (i in (shown - visible).coerceAtLeast(0)..(shown + visible).coerceAtMost(count - 1)) {
                val along = (i - shown) * stepPx + offset
                val angle = along / drumRadius
                if (drum && abs(angle) > 1.25f) continue
                val x = if (drum) cx + drumRadius * kotlin.math.sin(angle) else cx + along
                if (x < 0f || x > size.width) continue
                val isMajor = editor.major(i)
                val h = if (isMajor) 15.dp.toPx() else 10.dp.toPx()
                val fade = if (drum) kotlin.math.cos(angle).coerceIn(.15f, 1f)
                    else 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f) * .8f
                val thin = if (drum) .45f + .55f * kotlin.math.cos(angle) else 1f
                drawLine(
                    Color.White.copy(alpha = (if (enabled) (if (isMajor) .9f else .55f) else .2f) * fade),
                    Offset(x, tickBottom - h),
                    Offset(x, tickBottom),
                    strokeWidth = (if (isMajor) 1.6.dp.toPx() else 1.dp.toPx()) * thin
                )
                editor.label(i)?.let { text ->
                    val layout = measurer.measure(text, labelStyle)
                    if (drum && abs(x - cx) + layout.size.width / 2f > drumHalf) return@let
                    labels.add(Triple(x, fade, layout))
                }
            }
            val placed = ArrayList<Pair<Float, Float>>()
            val labelGap = 6.dp.toPx()
            labels.sortBy { abs(it.first - cx) }
            for ((x, fade, layout) in labels) {
                val half = layout.size.width / 2f
                if (abs(x - cx) < half + valueHalf + labelGap) continue
                if (placed.any { (px, ph) -> abs(x - px) < half + ph + labelGap }) continue
                placed.add(x to half)
                drawText(
                    layout,
                    color = Color.White.copy(alpha = (if (enabled) .5f else .2f) * fade),
                    topLeft = Offset(x - half, labelY)
                )
            }
            if (editor.endIcons) {
                val iconY = tickBottom - 26.dp.toPx()
                val iconInset = if (drum) cx - drumHalf + 6.dp.toPx() else 0f
                drawFlower(Offset(iconInset + 10.dp.toPx(), iconY), Color.White.copy(alpha = .6f))
                drawMountain(Offset(size.width - iconInset - 12.dp.toPx(), iconY), Color.White.copy(alpha = .6f))
            }
            // The needle starts under the value printed above it (Nov 18: in the shorter Classic windows it ran into it).
            val valueBottom = 4.dp.toPx() + 2.dp.toPx() + valueLayout.size.height
            val needleTop = maxOf(tickBottom - 25.dp.toPx(), valueBottom).coerceAtMost(tickBottom - 8.dp.toPx())
            drawLine(
                CaptureColors.Accent,
                Offset(cx, needleTop),
                Offset(cx, tickBottom + 2.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlower(center: Offset, color: Color) {
    val r = 2.4.dp.toPx()
    for (k in 0 until 5) {
        val a = Math.toRadians(k * 72.0 - 90.0)
        drawCircle(color, r, Offset(center.x + (r * 1.1f * Math.cos(a)).toFloat(), center.y + (r * 1.1f * Math.sin(a)).toFloat()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMountain(center: Offset, color: Color) {
    val w = 7.dp.toPx()
    val h = 6.dp.toPx()
    val path = Path().apply {
        moveTo(center.x - w, center.y + h / 2)
        lineTo(center.x - w / 3, center.y - h / 2)
        lineTo(center.x + w / 6, center.y + h / 6)
        lineTo(center.x + w / 2, center.y - h / 4)
        lineTo(center.x + w, center.y + h / 2)
        close()
    }
    drawPath(path, color)
    drawPath(path, color, style = Stroke(1.dp.toPx()))
}

// ---- Idle control area -----------------------------------------------------------------------------------------

/** Idle control area: Photo / Video (tap or swipe, stock size), or the last take's summary. */
@Composable
private fun X14uIdleControlArea(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    video: VideoControlState,
    showNumbers: Boolean,
    enabled: Boolean,
    /** Classic video: the slim band (no handle). */
    folded: Boolean = false
) {
    val haptics = LocalCaptureHaptics.current
    val extras = LocalCaptureExtras.current
    val classic = isClassicStyle()
    val current = currentX14uMode(state, extras.proUi, extras.pano, extras.burst)
    // Every mode, in the user's order (long-press one and drag it to move it, as the preset cards).
    val modeContext = LocalContext.current
    remember(modeContext) { X14uModeOrder.init(modeContext) }
    val stripModes = X14uModeOrder.modes
    var dismissed by remember { mutableStateOf<VideoTakeSummary?>(null) }
    val take = video.lastTake
    LaunchedEffect(take) {
        if (take != null) {
            kotlinx.coroutines.delay(if (take.hadProblems) 10_000L else 6_000L)
            dismissed = take
        }
    }
    val showTake = take != null && take !== dismissed && !video.recording &&
        state.captureMode == CaptureMode.Video && (take.hadProblems || video.diagnostics)
    when {
        video.recording && video.diagnostics && showNumbers -> X14uDiagnosticsLine(video)
        showTake -> X14uTakeSummary(take!!) { dismissed = take }
        else -> {
            var travel by remember { mutableStateOf(0f) }
            Column(
                Modifier
                    .fillMaxSize()
                    .pointerInput(enabled, current, stripModes) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = {
                                val threshold = 40.dp.toPx()
                                val index = stripModes.indexOf(current)
                                val target = when {
                                    travel < -threshold -> stripModes.getOrNull(index + 1)
                                    travel > threshold -> stripModes.getOrNull(index - 1)
                                    else -> null
                                }
                                if (target != null && target != current) {
                                    haptics.selection()
                                    selectX14uMode(target, state, dispatch, extras)
                                }
                                travel = 0f
                            }
                        ) { change, delta ->
                            change.consume()
                            travel += delta
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!folded) {
                    Box(Modifier.size(20.dp, 3.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(10.dp))
                }
                X14uModeRow(stripModes, current, enabled, classic) { mode ->
                    haptics.selection()
                    selectX14uMode(mode, state, dispatch, extras)
                }
            }
        }
    }
}

private fun timingLine(timing: com.rawr.camera.model.VideoTimingState, fps: Int): String =
    "FPS %.1f/%d · DROP %d · GPU %.0f/%.0f ms · ENC %.0f ms".format(
        timing.actualFps, fps, timing.dropped, timing.gpuMs, timing.gpuPeakMs, timing.encoderMs
    )

@Composable
private fun X14uDiagnosticsLine(video: VideoControlState) {
    val timing = video.timing
    Text(
        timing?.let { timingLine(it, video.fps) } ?: "Measuring…",
        fontFamily = X14uFont,
        fontSize = dpText(12f),
        color = if ((timing?.dropped ?: 0) > 0) CaptureColors.Danger else Color.White.copy(alpha = .9f),
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun X14uTakeSummary(take: VideoTakeSummary, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().captureClickable(shape = RoundedCornerShape(8.dp)) { onDismiss() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val t = take.timing
        Text(
            if (take.hadProblems) "Last take: ${t.dropped} dropped" + (t.dropReason?.let { " ($it)" } ?: "") else "Last take: OK",
            fontFamily = X14uFont,
            fontWeight = FontWeight.Medium,
            fontSize = dpText(13f),
            color = if (take.hadProblems) CaptureColors.Danger else CaptureColors.Success
        )
        Text(
            "%d:%02d · %.1f of %d fps · GPU peak %.0f ms · enc %.0f ms".format(
                take.seconds / 60, take.seconds % 60, t.actualFps, take.targetFps, t.gpuPeakMs, t.encoderPeakMs
            ),
            fontFamily = X14uFont,
            fontSize = dpText(11f),
            color = Color.White.copy(alpha = .75f),
            maxLines = 1
        )
    }
}

@Composable
private fun X14uRecordTimer(video: VideoControlState, orientation: Orientation, onTap: () -> Unit, modifier: Modifier) {
    val dropped = video.timing?.dropped ?: 0L
    val color = when (video.health) {
        VideoHealth.Good -> CaptureColors.Success
        VideoHealth.Tight -> Amber
        VideoHealth.Dropping -> CaptureColors.Danger
    }
    Row(
        modifier
            // The pill turns as a whole (background and all) in landscape.
            .uprightLayoutInLandscape(orientation)
            .background(Color.Black.copy(alpha = .4f), RoundedCornerShape(12.dp))
            .captureClickable(shape = RoundedCornerShape(12.dp), enabled = video.diagnostics) { onTap() }
            .semantics {
                contentDescription = "Video recording ${video.status}" + if (dropped > 0) ", $dropped frames dropped" else ""
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(video.status, color = Color.White, fontFamily = X14uFont, fontWeight = FontWeight.Medium, fontSize = dpText(13f),
            maxLines = 1, style = ViewfinderTextStyle)
        if (dropped > 0) {
            Text("· $dropped dropped", color = CaptureColors.Danger, fontFamily = X14uFont, fontWeight = FontWeight.Medium,
                fontSize = dpText(12f), maxLines = 1, style = ViewfinderTextStyle)
        }
    }
}

// ---- Shutter row -----------------------------------------------------------------------------------------------

@Composable
private fun X14uShutterRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    latestImageUri: Uri?,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit,
    onOpenLensSettings: (String) -> Unit,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    videoControls: VideoControlState,
    onToggleVideoRecording: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    pano: PanoShutter? = null,
    /** Burst mode: the hold-to-shoot shutter and Burst's own focus and format buttons. */
    burst: Boolean = false,
    /** Video: hold the record button to record with settings locked (null: tap only). */
    videoHold: VideoHold? = null,
    /** Timed burst on: the shutter starts the run (and stops it while it goes). */
    timed: TimedShutter? = null,
    /** The side buttons' alpha (dim while recording; the record button stays bright). */
    sideAlpha: Float = 1f
) {
    val isVideo = state.captureMode == CaptureMode.Video
    val extras = LocalCaptureExtras.current
    val orientation = state.orientation
    val turn = Modifier.uprightInLandscape(orientation)
    // The front-camera button returns to the last back lens.
    val front = state.capabilities.lenses.firstOrNull { isFrontLens(it.id, it.displayName) }
    var lastBack by remember { mutableStateOf<String?>(null) }
    val onFront = state.capabilities.lenses.firstOrNull { it.id == state.selectedLensId }
        ?.let { isFrontLens(it.id, it.displayName) } == true
    androidx.compose.runtime.SideEffect {
        if (!onFront && state.selectedLensId.isNotEmpty()) lastBack = state.selectedLensId
    }
    Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).alpha(sideAlpha), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                // Burst: the last run's frames stack behind the newest one, with their count.
                if (burst && state.burst.lastCount > 1 && !state.burst.running) {
                    Box(Modifier.uprightInLandscape(orientation).offset(x = 5.dp, y = (-5).dp).size(CaptureDimens.Thumbnail)
                        .background(Color(0xFF3A3530), RoundedCornerShape(12.dp)))
                }
                Thumbnail(state = state, imageUri = latestImageUri, onClick = onOpenLatestImage, onLongClick = onOpenRenderer,
                    modifier = turn)
                if (burst && state.burst.lastCount > 1 && !state.burst.running) {
                    Text(
                        "${state.burst.lastCount}",
                        color = Color(0xFF1A1A1A), fontFamily = X14uFont, fontWeight = FontWeight.Medium,
                        fontSize = dpText(11f), maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).uprightInLandscape(orientation)
                            .background(CaptureColors.Accent, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            // Pano: undo the last frame (during a set) instead of the front camera.
            if (pano != null) {
                X14uRoundButton(
                    X14uIcons.Undo,
                    "Undo the last pano frame",
                    orientation,
                    enabled = enabled && pano.onUndo != null,
                    size = 48.dp,
                    iconScale = .56f,
                    classicLabel = "UNDO"
                ) { pano.onUndo?.invoke() }
            } else X14uRoundButton(
                X14uIcons.CameraSwitch,
                if (onFront) "Switch to the back camera" else "Switch to the front camera",
                orientation,
                enabled = enabled && front != null,
                size = 48.dp,
                iconScale = .64f
            ) {
                val target = if (onFront) lastBack ?: state.capabilities.lenses.firstOrNull { !isFrontLens(it.id, it.displayName) }?.id
                else front?.id
                target?.let { dispatch(SelectLens(it)) }
            }
        }
        if (isVideo) {
            VideoRecordButton(
                state = videoControls,
                orientation = orientation,
                onClick = onToggleVideoRecording,
                countingDown = state.selfTimerRemainingMs != null,
                holdMs = videoHold?.holdMs ?: 0,
                onHoldLock = videoHold?.onLock,
                onHolding = { videoHold?.onHolding?.invoke(it) }
            )
        } else if (burst) {
            X14uBurstShutter(
                burst = state.burst,
                fps = com.rawr.camera.burst.BurstSettings.fps.collectAsState().value,
                orientation = orientation,
                onStart = { dispatch(com.rawr.camera.burst.BurstControl.start()) },
                onStop = { dispatch(com.rawr.camera.architecture.StopBurst) }
            )
        } else {
            Shutter(
                dispatch = when {
                    pano != null -> { action -> if (action is TriggerCapture) pano.onShoot() else dispatch(action) }
                    timed != null -> { action -> if (action is TriggerCapture) timed.onPress() else dispatch(action) }
                    // 50 mp on (checked at the press): the shot runs on the 50 MP lens (the viewfinder stays 12 MP with AF).
                    else -> { action -> if (action !is TriggerCapture || !extras.onFullSizeShot()) dispatch(action) }
                },
                modifier = Modifier,
                countingDown = state.selfTimerRemainingMs != null,
                longExposure = state.longExposure,
                stopSquare = pano?.locked == true || timed?.running == true
            )
        }
        Box(Modifier.weight(1f).alpha(sideAlpha), contentAlignment = Alignment.Center) {
            if (isVideo) {
                val muted = videoControls.audio.muted
                // Spread like the thumbnail and camera button on the other side.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    CompactSidePill(
                        icon = X14uIcons.LogCurve,
                        label = "LOG",
                        active = state.videoLogEnabled,
                        testTag = CaptureTestTags.TOPBAR_VIDEO_LOG,
                        contentDescription = "LOG ${if (state.videoLogEnabled) "on" else "off"}. Long press for video settings.",
                        onClick = { if (enabled) dispatch(ToggleVideoLog) },
                        onLongClick = extras.onOpenVideoSettings,
                        onLongClickLabel = "Video settings",
                        minWidth = SidePillWidth,
                        modifier = turn
                    )
                    CompactSidePill(
                        icon = if (muted) X14uIcons.MicOff else X14uIcons.Mic,
                        label = if (muted) "MUTED" else "AUDIO",
                        active = !muted,
                        testTag = "capture_x14u_audio",
                        contentDescription = if (muted) "Audio muted. Tap to record sound. Long press for audio settings."
                        else "Audio on. Tap to mute. Long press for audio settings.",
                        onClick = { if (enabled) extras.onToggleAudioMuted() },
                        onLongClick = extras.onOpenVideoSettings,
                        onLongClickLabel = "Audio settings",
                        minWidth = SidePillWidth,
                        modifier = turn
                    )
                }
            } else if (burst) {
                X14uBurstToggles(enabled = !state.burst.running, pillModifier = turn)
            } else {
                // Locked during a pano set: every frame keeps frame 1's merge and look.
                Box(Modifier.fillMaxWidth().alpha(if (pano?.locked == true) .45f else 1f)) {
                    PhotoLookToggles(
                        state, dispatch, onOpenFilmSimSettings, onOpenMultiframeSettings,
                        modifier = Modifier.fillMaxWidth(),
                        arrangement = Arrangement.SpaceEvenly,
                        pillModifier = turn
                    )
                    if (pano?.locked == true) Box(Modifier.matchParentSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    })
                }
            }
        }
    }
}

/** Where the Look / Exposure panels were left: the Look tab and the last setting picked per tab (SharedPreferences). */
private class PanelMemory(private val prefs: android.content.SharedPreferences) {
    var lookTab: String
        get() = prefs.getString("lookTab", "PROFILE") ?: "PROFILE"
        set(value) = prefs.edit().putString("lookTab", value).apply()

    fun keyFor(panel: String): String? = prefs.getString("key.$panel", null)

    fun remember(panel: String, key: String) = prefs.edit().putString("key.$panel", key).apply()
}

@Composable
private fun rememberPanelMemory(): PanelMemory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { PanelMemory(context.getSharedPreferences("x14u_panels", android.content.Context.MODE_PRIVATE)) }
}

internal class TimedShutter(val onPress: () -> Unit, val running: Boolean)

internal class VideoHold(val holdMs: Int, val onLock: () -> Unit, val onHolding: (Boolean) -> Unit)

/**
 * Hold-to-lock video: what a locked recording took over (exposure mode, focus mode, WB mode), given back when it
 * stops. Shutter and ISO: M; shutter only: S; ISO only: I (each keeps the live value it switches at).
 */
internal class X14uVideoLock {
    private var saved: Triple<ExposureMode, FocusMode, WhiteBalanceMode>? = null
    var active by mutableStateOf(false)
        private set
    var chips by mutableStateOf(emptyList<String>())
        private set

    fun lock(state: CaptureUiState, dispatch: CaptureDispatch, settings: com.rawr.camera.video.VideoLockSettings.Values) {
        saved = Triple(state.exposureControl.mode, state.focus.mode, state.whiteBalanceMode)
        val modes = state.capabilities.supportedExposureModes
        val target = when {
            settings.shutter && settings.iso -> ExposureMode.Manual
            settings.shutter -> ExposureMode.ShutterPriority
            settings.iso -> ExposureMode.IsoPriority
            else -> null
        }
        val list = mutableListOf<String>()
        if (target != null && target in modes) {
            if (state.exposureControl.mode != target) dispatch(SetExposureMode(target))
            if (settings.shutter) list += "S"
            if (settings.iso) list += "ISO"
        }
        if (settings.focus && state.focus.mode == FocusMode.Af) {
            dispatch(SetFocusMode(FocusMode.AfLock))
            list += "AF"
        } else if (settings.focus && state.focus.mode != FocusMode.Af) list += "AF"
        if (settings.whiteBalance) {
            if (state.whiteBalanceMode != WhiteBalanceMode.ManualTempTint && state.capabilities.manualWhiteBalanceSupported) {
                dispatch(LockWhiteBalance(state.whiteBalanceTemperatureK, state.whiteBalanceTint))
            }
            list += "WB"
        }
        chips = list
        active = true
    }

    fun release(state: CaptureUiState, dispatch: CaptureDispatch) {
        saved?.let { (exposure, focus, wb) ->
            if (exposure != state.exposureControl.mode && exposure in state.capabilities.supportedExposureModes) {
                dispatch(SetExposureMode(exposure))
            }
            if (focus != state.focus.mode) dispatch(SetFocusMode(focus))
            if (wb != WhiteBalanceMode.ManualTempTint && wb != state.whiteBalanceMode) dispatch(SetWhiteBalanceMode(wb))
        }
        saved = null
        chips = emptyList()
        active = false
    }
}

/**
 * The mode names in even slots (Nov 19). Tap = that mode. Long-press and drag = move it along the strip (the others make
 * room); the order is kept (X14uModeOrder).
 */
@Composable
private fun X14uModeRow(
    modes: List<X14uMode>,
    current: X14uMode,
    enabled: Boolean,
    classic: Boolean,
    onSelect: (X14uMode) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val slot = 66.dp
    val stepPx = with(LocalDensity.current) { slot.toPx() }
    val order = remember(modes) { modes.toMutableStateList() }
    // Followed in screen coordinates (RowReorder): moving a mode no longer jumps back and forth.
    val reorderScope = androidx.compose.runtime.rememberCoroutineScope()
    val reorder = remember(stepPx) { RowReorder<X14uMode>(stepPx, reorderScope) }
    val modesNow by rememberUpdatedState(modes)
    val selectNow by rememberUpdatedState(onSelect)
    val currentNow by rememberUpdatedState(current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        order.forEach { mode ->
            key(mode) {
                val isSelected = current == mode
                val dragging = reorder.dragging == mode
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .pointerInput(mode, enabled) {
                            if (!enabled) return@pointerInput
                            detectTapGestures { if (mode != currentNow) selectNow(mode) }
                        }
                        .then(with(reorder) {
                            Modifier.reorderSlot(
                                key = mode,
                                enabled = enabled,
                                order = order,
                                onStart = { haptics.selection() },
                                onMove = { from, to ->
                                    order.add(to, order.removeAt(from))
                                    haptics.selection()
                                },
                                onEnd = { if (order.toList() != modesNow) X14uModeOrder.set(order.toList()) },
                                onCancel = {
                                    order.clear()
                                    order.addAll(modesNow)
                                }
                            )
                        })
                        .graphicsLayer {
                            translationX = reorder.translation(mode)
                            val k = if (dragging) 1.12f else 1f
                            scaleX = k
                            scaleY = k
                        }
                        .width(slot)
                        .height(36.dp)
                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                            contentDescription = mode.label + ". Long-press and drag to move."
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        fontFamily = X14uFont,
                        fontSize = dpText(15f),
                        maxLines = 1,
                        color = when {
                            isSelected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .4f)
                            classic -> ClassicColors.Engraved.copy(alpha = if (enabled) .85f else .25f)
                            else -> Color.White.copy(alpha = if (enabled) .85f else .25f)
                        }
                    )
                }
            }
        }
    }
}
