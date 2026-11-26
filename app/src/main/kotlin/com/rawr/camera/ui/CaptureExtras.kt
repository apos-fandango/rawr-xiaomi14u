package com.rawr.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** X14U capture-screen shortcuts that open Settings pages or flip a setting, provided by the activity. */
data class CaptureExtras(
    val onToggleAudioMuted: () -> Unit = {},
    val onOpenVideoSettings: () -> Unit = {},
    val onOpenExposureSettings: () -> Unit = {},
    /** Photo+ (the full-control stills mode) rather than Photo. */
    val proUi: Boolean = true,
    val onSetProUi: (Boolean) -> Unit = {},
    /** X14U tools panel (swipe down on the viewfinder). */
    val tools: com.rawr.camera.CaptureViewModel.ToolsState = com.rawr.camera.CaptureViewModel.ToolsState(),
    val onSetPhotoAspect: (com.rawr.camera.settings.model.PhotoAspect) -> Unit = {},
    /** Pano: the merge's projection (the Projection tile). */
    val onSetPanoProjection: (com.rawr.camera.settings.model.PanoProjection) -> Unit = {},
    val onToggleLevel: () -> Unit = {},
    /** Video horizon Lock / Follow (the Level tile); either shows the level too. */
    val onSetHorizonMode: (lock: Boolean, follow: Boolean) -> Unit = { _, _ -> },
    /** Video stabilisation (the Stabilise tile). */
    val onSetStabilize: (com.rawr.camera.settings.model.VideoStabilize) -> Unit = {},
    val onSetLocationTagging: (Boolean) -> Unit = {},
    val onToggleLensMirror: (String) -> Unit = {},
    /** Show the one-time "Swipe up for Photo+" hint (until switched once, at most twice). */
    val stillsHint: Boolean = false,
    val onStillsHintShown: () -> Unit = {},
    /** Digital zoom crop on top of the lens (1 = none). */
    val onDigitalZoom: (Float) -> Unit = {},
    /** Pano mode (after Video): stills run as Photo with the Pano guide. */
    val pano: Boolean = false,
    val onSetPano: (Boolean) -> Unit = {},
    /** Burst mode (between Video and Pano): hold the shutter to shoot; stills run as Photo, one frame each. */
    val burst: Boolean = false,
    val onSetBurst: (Boolean) -> Unit = {},
    /** Burst pre-capture: arm the ring (seconds, fps; 0 seconds = off) and how many frames it holds now. */
    val onArmPrecapture: (Int, Int) -> Unit = { _, _ -> },
    val precaptureHeld: () -> Int = { 0 },
    /** Delete the files of a Pano frame by its name tag. */
    val onPanoUndo: (String) -> Unit = {},
    /** A Pano set ended: merge its frames (tag, frame count, start time) when the setting is on. */
    val onPanoMerge: (tag: String, frames: Int, startedAtMillis: Long, spanDegrees: Float, vertical: Boolean) -> Unit =
        { _, _, _, _, _ -> },
    /** Pano merge progress / result for the viewfinder (null = nothing to show). */
    val panoStatus: String? = null,
    /** The lens's 50 mp choice is on: the shutter takes the shot on the run-as lens (12 MP viewfinder keeps AF). */
    val fullSizeShot: Boolean = false,
    /** A 50 MP shot is running: lens row and controls are locked until it's back. */
    val fullSizeBusy: Boolean = false,
    /** The shutter press: true when it was taken as a 50 MP shot, false for a normal shot. */
    val onFullSizeShot: () -> Boolean = { false },
    /** X14U presets (P0 baseline, P1…): the badge above Look and the preset strip. */
    val presets: PresetsUi = PresetsUi(),
    val presetActions: PresetActions = PresetActions.None,
    /** X14U film recipes: the FILM plate next to the preset plate and its strip. */
    val filmRecipes: FilmRecipesUi = FilmRecipesUi(),
    val filmRecipeActions: FilmRecipeActions = FilmRecipeActions.None
)

val LocalCaptureExtras = staticCompositionLocalOf { CaptureExtras() }
