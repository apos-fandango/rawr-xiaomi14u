package com.rawr.camera.ui

import com.rawr.camera.settings.model.CapturePreset
import com.rawr.camera.settings.model.PresetCamera
import com.rawr.camera.settings.model.PresetGroup

/** One card in the preset strip. */
data class PresetItem(
    val id: String,
    /** "P0", "P1"… by position. */
    val label: String,
    val name: String,
    val groups: Set<PresetGroup>,
    val camera: PresetCamera,
    /** A short line per settings group for the details sheet. */
    val summary: Map<PresetGroup, String> = emptyMap()
) {
    val isBaseline: Boolean get() = id == CapturePreset.BASELINE_ID
}

/** X14U presets for the capture screen (see CapturePresets.kt). */
data class PresetsUi(
    val items: List<PresetItem> = emptyList(),
    val activeId: String = CapturePreset.BASELINE_ID,
    /** The active preset's settings (look, film, processing, AE options) differ from the live ones. */
    val settingsChanged: Boolean = false,
    /** The live settings in the same short form as [PresetItem.summary] (save sheet). */
    val current: Map<PresetGroup, String> = emptyMap()
) {
    val active: PresetItem? get() = items.firstOrNull { it.id == activeId }
    val full: Boolean get() = items.size >= CapturePreset.MAX_PRESETS
}

/** What the capture screen can do with presets; the camera part (exposure, WB, lens) is applied by the screen. */
interface PresetActions {
    /** The preset's settings groups into the live settings; it becomes the active one. */
    fun apply(id: String) {}
    fun save(name: String, groups: Set<PresetGroup>, camera: PresetCamera) {}
    /** The live settings (and [camera]) into the preset, keeping its groups. */
    fun update(id: String, camera: PresetCamera) {}
    fun rename(id: String, name: String) {}
    /** Leaves [group] out of the preset from now on (its values go; a preset keeps at least one group). */
    fun removeGroup(id: String, group: PresetGroup) {}
    fun duplicate(id: String) {}
    fun delete(id: String) {}
    /** Strip positions (P0 stays first). */
    fun move(from: Int, to: Int) {}
    /** P0 back to the app's defaults (its saved values only; nothing live changes). */
    fun restoreBaseline() {}
    /** RESET in the Look / Exposure pills: these groups back to P0's values (the active preset stays). */
    fun resetToBaseline(groups: Set<PresetGroup>) {}

    companion object {
        val None = object : PresetActions {}
    }
}
