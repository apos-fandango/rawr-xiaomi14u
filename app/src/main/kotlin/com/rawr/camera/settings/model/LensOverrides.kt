package com.rawr.camera.settings.model

import com.rawr.camera.model.TonemapControlContract
import com.rawr.camera.settings.architecture.editActiveProfileTone
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Per-lens overrides (X14U build). A lens can replace some global settings with its own value: Rawr AE, tone,
 * detail and multiframe settings. [LensProfile.overrides] stores them by key as text; everything not overridden
 * follows the global setting. The capture screen, the camera and captures see the settings with the active lens's
 * overrides applied ([withLensOverrides]); Settings pages keep editing the global values.
 *
 * The active lens is the lens the capture screen shows ([SettingsValues.lastLensId]). A lens option choice that
 * runs as another lens (50 MP -> "50MP") uses that lens's overrides; in video a lens running as its video lens
 * keeps its own overrides.
 */
sealed interface LensOverrideKind {
    /** One of [values] (stored id to label). */
    data class Choice(val values: List<Pair<String, String>>) : LensOverrideKind

    /** A number from [min] to [max] in [step]s, shown with [decimals] and [unit]. */
    data class Number(val min: Float, val max: Float, val step: Float, val decimals: Int, val unit: String = "") :
        LensOverrideKind

    data object Toggle : LensOverrideKind
}

class LensOverrideSpec(
    val key: String,
    val group: String,
    val title: String,
    val kind: LensOverrideKind,
    val read: (SettingsValues) -> String,
    val write: (SettingsValues, String) -> SettingsValues
) {
    /** [value] as the settings show it. */
    fun label(value: String): String = when (val k = kind) {
        is LensOverrideKind.Choice -> k.values.firstOrNull { it.first == value }?.second ?: value
        is LensOverrideKind.Number -> value.toFloatOrNull()?.let { formatNumber(it, k) } ?: value
        LensOverrideKind.Toggle -> if (value == "true") "On" else "Off"
    }

    fun labelOf(values: SettingsValues): String = label(read(values))
}

internal fun formatNumber(value: Float, kind: LensOverrideKind.Number): String {
    val text = if (kind.decimals == 0) value.roundToInt().toString()
    else String.format(Locale.US, "%.${kind.decimals}f", value)
    val signed = if (kind.min < 0f && value > 0f) "+$text" else text
    return if (kind.unit.isEmpty()) signed else "$signed ${kind.unit}"
}

object LensOverrides {
    const val GROUP_AE = "Rawr AE"
    const val GROUP_TONE = "Tone"
    const val GROUP_DETAIL = "Detail"
    const val GROUP_MULTIFRAME = "Multiframe"

    const val HDR_PLUS = "multiframe.enabled"

    private inline fun <reified E : Enum<E>> choice(label: (E) -> String, entries: List<E> = enumValues<E>().toList()) =
        LensOverrideKind.Choice(entries.map { it.name to label(it) })

    private inline fun <reified E : Enum<E>> enumOf(value: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: fallback

    private fun float(value: String, fallback: Float, min: Float, max: Float): Float =
        value.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: fallback

    private fun num(value: Float): String = if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()

    private fun tone(key: String, title: String, get: (ProfileTone) -> Float, set: (ProfileTone, Float) -> ProfileTone) =
        LensOverrideSpec(
            key, GROUP_TONE, title,
            LensOverrideKind.Number(TonemapControlContract.TONE_UI_MIN, TonemapControlContract.TONE_UI_MAX, 1f, 0),
            read = { num(get(it.activeProfileTone())) },
            write = { s, v ->
                val value = float(v, 0f, TonemapControlContract.TONE_UI_MIN, TonemapControlContract.TONE_UI_MAX)
                s.editActiveProfileTone { set(it, value) }
            }
        )

    private val highlightModes = listOf("off" to "Off", "propagation" to "Propagation", "opposed" to "Inpaint Opposed")
    private val sharpening = listOf("off" to "Off") + CaptureSharpeningLevel.entries.map { it.name to it.label }

    val all: List<LensOverrideSpec> = listOf(
        // Rawr AE
        LensOverrideSpec("ae.nightLook", GROUP_AE, "Night Look", choice<RawrNightLook>({ it.label }),
            read = { it.rawrAe.nightLook.name },
            write = { s, v -> s.copy(rawrAe = s.rawrAe.copy(nightLook = enumOf(v, s.rawrAe.nightLook))) }),
        LensOverrideSpec("ae.balance", GROUP_AE, "Shutter / ISO Balance", choice<RawrBalance>({ it.label }),
            read = { it.rawrAe.balance.name },
            write = { s, v -> s.copy(rawrAe = s.rawrAe.copy(balance = enumOf(v, s.rawrAe.balance))) }),
        LensOverrideSpec("ae.maxShutter", GROUP_AE, "Slowest Shutter", choice<RawrMaxShutter>({ it.label }),
            read = { it.rawrAe.maxShutter.name },
            write = { s, v -> s.copy(rawrAe = s.rawrAe.copy(maxShutter = enumOf(v, s.rawrAe.maxShutter))) }),
        LensOverrideSpec("ae.metering", GROUP_AE, "Metering", choice<RawrMetering>({ it.label }),
            read = { it.rawrAe.metering.name },
            write = { s, v -> s.copy(rawrAe = s.rawrAe.copy(metering = enumOf(v, s.rawrAe.metering))) }),
        LensOverrideSpec("ae.digitalGain", GROUP_AE, "Digital Gain", choice<RawrDigitalGain>({ it.label }),
            read = { it.rawrAe.digitalGain.name },
            write = { s, v -> s.copy(rawrAe = s.rawrAe.copy(digitalGain = enumOf(v, s.rawrAe.digitalGain))) }),
        // Tone (the active colour profile's tone)
        LensOverrideSpec("tone.renderExposure", GROUP_TONE, "Render Exposure",
            LensOverrideKind.Number(TonemapControlContract.EXPOSURE_MIN_EV, TonemapControlContract.EXPOSURE_MAX_EV, 0.1f, 1, "EV"),
            read = { num(it.activeProfileTone().renderExposure) },
            write = { s, v ->
                val ev = float(v, 0f, TonemapControlContract.EXPOSURE_MIN_EV, TonemapControlContract.EXPOSURE_MAX_EV)
                s.editActiveProfileTone { it.copy(renderExposure = ev) }
            }),
        tone("tone.contrast", "Contrast", { it.contrast }) { t, v -> t.copy(contrast = v) },
        tone("tone.highlights", "Highlights", { it.highlights }) { t, v -> t.copy(highlights = v) },
        tone("tone.shadows", "Shadows", { it.shadows }) { t, v -> t.copy(shadows = v) },
        tone("tone.midtones", "Midtones", { it.midtones }) { t, v -> t.copy(midtones = v) },
        LensOverrideSpec("tone.dynamicRange", GROUP_TONE, "Dynamic Range",
            choice<AutoKeyExposure>({ it.label }),
            read = { it.autoKeyExposure.name },
            write = { s, v -> s.copy(autoKeyExposure = enumOf(v, s.autoKeyExposure)) }),
        LensOverrideSpec("tone.highlightReconstruction", GROUP_TONE, "Highlight Reconstruction",
            LensOverrideKind.Choice(highlightModes),
            read = { if (!it.photoHighlightEnabled) "off" else if (it.photoHighlightMethod == 0) "propagation" else "opposed" },
            write = { s, v ->
                when (v) {
                    "off" -> s.copy(photoHighlightEnabled = false)
                    "propagation" -> s.copy(photoHighlightEnabled = true, photoHighlightMethod = 0)
                    else -> s.copy(photoHighlightEnabled = true, photoHighlightMethod = 1)
                }
            }),
        LensOverrideSpec("tone.highlightCompression", GROUP_TONE, "Highlight Compression",
            LensOverrideKind.Number(0f, 300f, 5f, 0),
            read = { num(it.photoHighlightCompression) },
            write = { s, v -> s.copy(photoHighlightCompression = float(v, s.photoHighlightCompression, 0f, 300f)) }),
        // Detail
        LensOverrideSpec("detail.sharpening", GROUP_DETAIL, "Capture Sharpening", LensOverrideKind.Choice(sharpening),
            read = { if (!it.photoCaptureSharpeningEnabled) "off" else it.photoCaptureSharpeningLevel.name },
            write = { s, v ->
                if (v == "off") s.copy(photoCaptureSharpeningEnabled = false)
                else s.copy(photoCaptureSharpeningEnabled = true,
                    photoCaptureSharpeningLevel = enumOf(v, s.photoCaptureSharpeningLevel))
            }),
        LensOverrideSpec("detail.demosaic", GROUP_DETAIL, "Demosaic", choice<DemosaicAlgorithm>({ it.label }),
            read = { it.demosaicAlgorithm.name },
            write = { s, v -> s.copy(demosaicAlgorithm = enumOf(v, s.demosaicAlgorithm)) }),
        LensOverrideSpec("detail.denoise", GROUP_DETAIL, "Denoise", LensOverrideKind.Toggle,
            read = { (it.photoDenoise is DenoiseConfig.Wavelet).toString() },
            write = { s, v -> s.copy(photoDenoise = s.photoDenoise.withMaster(v == "true")) }),
        LensOverrideSpec("detail.denoiseStrength", GROUP_DETAIL, "Denoise Strength",
            LensOverrideKind.Number(0f, 8f, 0.1f, 1),
            read = { num(it.photoDenoise.config.strength) },
            write = { s, v -> s.copy(photoDenoise = s.photoDenoise.withWaveletStrength(float(v, 1f, 0f, 8f))) }),
        LensOverrideSpec("detail.denoiseLuma", GROUP_DETAIL, "Denoise Luma",
            LensOverrideKind.Number(0f, 1f, 0.05f, 2),
            read = { num(it.photoDenoise.config.luma) },
            write = { s, v -> s.copy(photoDenoise = s.photoDenoise.withWaveletLuma(float(v, 0.25f, 0f, 1f))) }),
        LensOverrideSpec("detail.defringe", GROUP_DETAIL, "Defringe", LensOverrideKind.Toggle,
            read = { it.photoDefringeEnabled.toString() },
            write = { s, v -> s.copy(photoDefringeEnabled = v == "true") }),
        LensOverrideSpec("detail.defringeStrength", GROUP_DETAIL, "Defringe Strength",
            LensOverrideKind.Number(0f, 2f, 0.1f, 1),
            read = { num(it.photoDefringeStrength) },
            write = { s, v -> s.copy(photoDefringeStrength = float(v, s.photoDefringeStrength, 0f, 2f)) }),
        LensOverrideSpec("detail.fccSteps", GROUP_DETAIL, "False Colour Correction Steps",
            LensOverrideKind.Number(1f, 8f, 1f, 0),
            read = { it.photoFccSteps.toString() },
            write = { s, v -> s.copy(photoFccSteps = float(v, s.photoFccSteps.toFloat(), 1f, 8f).roundToInt()) }),
        // Multiframe
        LensOverrideSpec(HDR_PLUS, GROUP_MULTIFRAME, "HDR+ (multiframe) at start", LensOverrideKind.Toggle,
            read = { it.experimentalMultiframeEnabled.toString() },
            write = { s, v -> s.copy(experimentalMultiframeEnabled = v == "true") }),
        LensOverrideSpec("multiframe.merge", GROUP_MULTIFRAME, "Merge Algorithm",
            choice<MultiframeMergeAlgorithm>({ it.label }, MultiframeMergeAlgorithm.entries),
            read = { it.multiframeTuning.mergeAlgorithm.name },
            write = { s, v ->
                s.copy(multiframeTuning = s.multiframeTuning.copy(
                    mergeAlgorithm = enumOf(v, s.multiframeTuning.mergeAlgorithm)
                        ?: s.multiframeTuning.mergeAlgorithm))
            }),
        LensOverrideSpec("multiframe.frames", GROUP_MULTIFRAME, "Maximum Frames",
            LensOverrideKind.Number(2f, MAX_BURST_FRAMES.toFloat(), 1f, 0),
            read = { it.multiframeTuning.maxFrames.toString() },
            write = { s, v ->
                s.copy(multiframeTuning = s.multiframeTuning.copy(
                    maxFrames = float(v, s.multiframeTuning.maxFrames.toFloat(), 2f, MAX_BURST_FRAMES.toFloat()).roundToInt()))
            }),
        LensOverrideSpec("multiframe.reference", GROUP_MULTIFRAME, "Reference Frame",
            choice<MultiframeBaseFrameMode>({ it.label }),
            read = { it.multiframeBaseFrameMode.name },
            write = { s, v -> s.copy(multiframeBaseFrameMode = enumOf(v, s.multiframeBaseFrameMode)) })
    )

    private val byKey = all.associateBy { it.key }

    fun spec(key: String): LensOverrideSpec? = byKey[key]

    val groups: List<String> = listOf(GROUP_AE, GROUP_TONE, GROUP_DETAIL, GROUP_MULTIFRAME)

    /** [values] with [overrides] applied; unknown keys are ignored. */
    fun apply(values: SettingsValues, overrides: Map<String, String>): SettingsValues =
        overrides.entries.fold(values) { acc, (key, value) -> byKey[key]?.write?.invoke(acc, value) ?: acc }
}

private fun sameName(a: String?, b: String?): Boolean =
    a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

/**
 * The lens whose overrides apply now: the capture screen's lens, or the lens one of its chosen option choices runs
 * as (photo mode). In video a lens running as its video lens keeps its own overrides.
 */
fun SettingsValues.overrideLens(runAs: Boolean = true): LensProfile? {
    val lenses = lensProfiles ?: return null
    val lens = lenses.firstOrNull { sameName(it.name, lastLensId) } ?: return null
    if (isVideo || !runAs) return lens
    val runAs = lens.runAsTarget() ?: return lens
    return lenses.firstOrNull { sameName(it.name, runAs) } ?: lens
}

/** The lens a chosen option choice of this lens runs as ("50MP"), or null. */
fun LensProfile.runAsTarget(): String? {
    val picks = options.effectivePicks()
    return options.indices.asSequence()
        .mapNotNull { row -> options[row].choices.getOrNull(picks[row])?.runAs?.trim()?.takeIf { it.isNotEmpty() } }
        .firstOrNull()
}

/** X14U 3x 50 MP: the chosen option runs this lens as one whose viewfinder is the camera-processed companion. */
fun LensProfile.runsAsCompanion(lenses: List<LensProfile>): Boolean {
    val target = runAsTarget() ?: return false
    return lenses.firstOrNull { it.name.trim().equals(target, ignoreCase = true) }?.session?.companionViewfinder == true
}

/** True when the lens holds overrides for this lens row's pill dot (its own or its run-as lens's). */
fun LensProfile.hasOverrides(): Boolean = overrides.isNotEmpty()

/**
 * Settings with the active lens's overrides applied (what the camera and captures use). [runAs] false: the lens's own
 * overrides even while a choice runs the shot as another lens (X14U Pano and Burst shoot 12 MP frames).
 */
fun SettingsValues.withLensOverrides(runAs: Boolean = true): SettingsValues {
    val overrides = overrideLens(runAs)?.overrides.orEmpty()
    return if (overrides.isEmpty()) this else LensOverrides.apply(this, overrides)
}

/** [lensName]'s overrides replaced by [overrides] ([deviceDefaults] when the user list isn't customised yet). */
fun SettingsValues.withLensOverrideMap(
    lensName: String,
    overrides: Map<String, String>,
    deviceDefaults: List<LensProfile> = emptyList()
): SettingsValues {
    val lenses = lensProfiles ?: deviceDefaults
    if (lenses.none { sameName(it.name, lensName) }) return this
    return copy(lensProfiles = lenses.map { if (sameName(it.name, lensName)) it.copy(overrides = overrides) else it })
}

/**
 * Applies a capture-screen [edit] to the settings the user sees (the active lens's overrides applied): a setting the
 * lens overrides is written to the override, the rest to the global value. Keys in [perLens] that the edit changes
 * always become an override of the active lens (the HDR+ button remembers its state per lens). The edit must not
 * switch the capture mode or colour profile (tone overrides follow the active profile).
 */
fun SettingsValues.editThroughLens(
    perLens: Set<String> = emptySet(),
    deviceDefaults: List<LensProfile> = emptyList(),
    edit: (SettingsValues) -> SettingsValues
): SettingsValues {
    val lens = overrideLens() ?: (lensProfiles ?: deviceDefaults).firstOrNull { sameName(it.name, lastLensId) }
        ?: return edit(this)
    val overrides = lens.overrides
    val effective = if (overrides.isEmpty()) this else LensOverrides.apply(this, overrides)
    val edited = edit(effective)
    var base = edited
    val next = overrides.toMutableMap()
    LensOverrides.all.forEach { spec ->
        val key = spec.key
        val now = spec.read(edited)
        val changed = now != spec.read(effective)
        if (key in overrides || (key in perLens && changed)) {
            if (changed) next[key] = now
            base = spec.write(base, spec.read(this))
        }
    }
    return if (next == overrides) base else base.withLensOverrideMap(lens.name, next, deviceDefaults)
}
