package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawr.camera.model.Orientation
import com.rawr.camera.settings.model.CapturePreset
import com.rawr.camera.settings.model.PresetCamera
import com.rawr.camera.settings.model.PresetGroup

private val PresetMono = FontFamily.Monospace

/**
 * The preset badge above the Look button: just its text ("P0", "P1", "P1*"), light grey for P0, accent for any
 * other preset. A tap opens the preset strip.
 */
@Composable
internal fun X14uPresetBadge(
    label: String,
    accent: Boolean,
    enabled: Boolean,
    orientation: Orientation,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(36.dp, 24.dp)
            .clickable(enabled = enabled, onClick = onTap)
            .testTag("x14u_preset_badge"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = PresetMono,
            fontSize = dpText(12.5f),
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            color = if (accent) CaptureColors.Accent else Color.White.copy(alpha = .55f),
            maxLines = 1,
            modifier = Modifier.uprightLayoutInLandscape(orientation)
        )
    }
}

/**
 * The active preset under the shutter row (Nov 19): its number and name ("*" once changed) on a small nameplate,
 * brushed metal in Classic / Bronze, a dark pill in Minimal. Tap = the preset strip. In landscape only the number,
 * turned with the phone.
 */
@Composable
internal fun X14uPresetPlate(
    label: String,
    name: String,
    accent: Boolean,
    enabled: Boolean,
    orientation: Orientation,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "x14u_preset_badge",
    description: String = "Preset"
) {
    val classic = isClassicStyle()
    val shape = RoundedCornerShape(if (classic) 5.dp else 12.dp)
    val engraved = if (classic) ClassicColors.Engraved else Color.White
    val landscape = orientation == Orientation.Landscape
    Row(
        modifier
            .height(26.dp)
            .widthIn(min = 44.dp, max = 180.dp)
            .clip(shape)
            .then(
                if (classic) Modifier.drawBehind {
                    drawMetalPlate(Offset.Zero, size, ClassicColors.metal(0x2A))
                } else Modifier.background(Color.White.copy(alpha = .09f))
            )
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 10.dp)
            .testTag(tag)
            .semantics { contentDescription = "$description $label $name. Tap for the strip." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            fontFamily = PresetMono,
            fontSize = dpText(12.5f),
            fontWeight = FontWeight.Medium,
            color = if (accent) CaptureColors.Accent else engraved.copy(alpha = .7f),
            maxLines = 1,
            modifier = Modifier.uprightLayoutInLandscape(orientation)
        )
        if (!landscape && name.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                fontFamily = X14uFont,
                fontSize = dpText(12.5f),
                color = engraved.copy(alpha = .9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The preset strip, where the mode names are: a card per preset (P0 first), then "+ Save". Tap a card to apply it,
 * tap the active one for its details; long-press and drag a card to move it (P0 stays first, numbers follow the
 * order). When the active preset was changed after applying, a line above offers Update and Revert.
 */
@Composable
internal fun X14uPresetStrip(
    presets: PresetsUi,
    /** The active preset differs from what is set now (settings or camera). */
    changed: Boolean,
    enabled: Boolean,
    onApply: (PresetItem) -> Unit,
    onDetails: (PresetItem) -> Unit,
    onSaveNew: () -> Unit,
    onUpdate: () -> Unit,
    onRevert: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val accent = CaptureColors.Accent
    val cardWidth = 104.dp
    val cardFill = if (isClassicStyle()) Color(0xEB0A0A0A) else X14uPanelStyle.Track
    val gap = 7.dp
    val stepPx = with(LocalDensity.current) { (cardWidth + gap).toPx() }
    // Local order while a card is dragged; the stored order follows on release.
    val order = remember(presets.items.map { it.id }) { presets.items.map { it.id }.toMutableStateList() }
    // Long-press a card and drag it (P0 stays first); followed in screen coordinates (RowReorder).
    val reorderScope = rememberCoroutineScope()
    val reorder = remember(stepPx) { RowReorder<String>(stepPx, reorderScope, minIndex = 1) }
    val dragId = reorder.dragging
    val items by rememberUpdatedState(presets.items)
    // Gesture handlers outlive a recomposition: they read these.
    val activeNow by rememberUpdatedState(presets.activeId)
    val applyNow by rememberUpdatedState(onApply)
    val detailsNow by rememberUpdatedState(onDetails)
    val moveNow by rememberUpdatedState(onMove)
    // Nov 18 (as stock): the strip takes the readout row and the slot, a header on top (title, or "P1 changed ·
    // Update · Revert"; + saves a new one) and taller cards with what each holds. ··· on every card opens its details
    // without applying it.
    val engraved = if (isClassicStyle()) ClassicColors.Engraved else Color.White
    Column(Modifier.fillMaxSize().testTag("x14u_preset_strip")) {
        val active = presets.active
        Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp)) {
            if (changed && active != null) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Text("${active.label} changed", fontFamily = X14uFont, fontSize = dpText(13f), color = engraved.copy(alpha = .75f))
                    Spacer(Modifier.width(14.dp))
                    Text("Update", fontFamily = X14uFont, fontSize = dpText(13.5f), color = accent,
                        modifier = Modifier.clickable(enabled = enabled) { haptics.selection(); onUpdate() }.padding(horizontal = 4.dp, vertical = 6.dp))
                    Text(" · ", fontFamily = X14uFont, fontSize = dpText(13f), color = Color.White.copy(alpha = .4f))
                    Text("Revert", fontFamily = X14uFont, fontSize = dpText(13.5f), color = engraved.copy(alpha = .9f),
                        modifier = Modifier.clickable(enabled = enabled) { haptics.selection(); onRevert() }.padding(horizontal = 4.dp, vertical = 6.dp))
                }
            } else {
                Text("Presets", fontFamily = X14uFont, fontSize = dpText(15f), color = engraved,
                    modifier = Modifier.align(Alignment.Center))
            }
            if (!presets.full) Box(
                Modifier.align(Alignment.CenterEnd).size(40.dp, 36.dp)
                    .clickable(enabled = enabled) { haptics.selection(); onSaveNew() }
                    .testTag("x14u_preset_save")
                    .semantics { contentDescription = "Save the current settings as a new preset" },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontFamily = X14uFont, fontSize = dpText(24f), color = engraved)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(rememberScrollState(), enabled = dragId == null)
                // Room for the selected card's outline (it was clipped at the strip's edges in the mockup).
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // key() straight in the loop (no `if` around it): an `if` gives each place in the row its own group, so a
            // keyed card couldn't move between places; the dragged card was rebuilt on the first swap and its drag
            // ended (the strip "glitched" while the mode row, keyed directly, worked). Nor an early return inside
            // key {}: D8 can't take the class the compiler makes for it.
            order.mapNotNull { id -> presets.items.firstOrNull { it.id == id } }.forEach { item ->
                val id = item.id
                key(id) {
                    val selected = id == presets.activeId
                    val dragging = id == dragId
                    Box(
                        Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .pointerInput(id, enabled) {
                                if (!enabled) return@pointerInput
                                detectTapGestures {
                                    haptics.selection()
                                    val now = items.firstOrNull { it.id == id } ?: return@detectTapGestures
                                    if (now.id == activeNow) detailsNow(now) else applyNow(now)
                                }
                            }
                            .then(with(reorder) {
                                Modifier.reorderSlot(
                                    key = id,
                                    enabled = enabled && !item.isBaseline,
                                    order = order,
                                    onStart = { haptics.selection() },
                                    onMove = { from, to ->
                                        order.add(to, order.removeAt(from))
                                        haptics.selection()
                                    },
                                    onEnd = {
                                        val from = items.indexOfFirst { it.id == id }
                                        val to = order.indexOf(id)
                                        if (from >= 1 && to >= 1 && from != to) moveNow(from, to)
                                    },
                                    onCancel = {
                                        order.clear()
                                        order.addAll(items.map { it.id })
                                    }
                                )
                            })
                            .graphicsLayer {
                                translationX = reorder.translation(id)
                                val k = if (dragging) 1.06f else 1f
                                scaleX = k
                                scaleY = k
                            }
                            .width(cardWidth)
                            .heightIn(max = 84.dp)
                            .fillMaxHeight()
                            .background(cardFill, RoundedCornerShape(12.dp))
                            .then(
                                if (selected) Modifier.border(1.2.dp, accent, RoundedCornerShape(12.dp))
                                else Modifier.border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(12.dp))
                            )
                            .padding(start = 9.dp, end = 4.dp, top = 6.dp, bottom = 7.dp)
                    ) {
                        // The card's number goes by where it is now (also while it is dragged).
                        Text(
                            "P${order.indexOf(id)}",
                            fontFamily = PresetMono,
                            fontSize = dpText(12.5f),
                            color = if (selected) accent else engraved.copy(alpha = .85f),
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                        // ···: this card's details without applying it (as stock).
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(y = (-6).dp).size(34.dp, 28.dp)
                                .clickable(enabled = enabled) { haptics.selection(); detailsNow(item) }
                                .semantics { contentDescription = "${item.label} ${item.name} details" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("···", fontSize = dpText(13f), color = engraved.copy(alpha = .7f))
                        }
                        Column(Modifier.align(Alignment.CenterStart).padding(top = 6.dp, end = 4.dp)) {
                            presetCardLines(item).forEach { line ->
                                Text(line, fontFamily = X14uFont, fontSize = dpText(10.5f), lineHeight = dpText(13f),
                                    color = Color.White.copy(alpha = .5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            item.name,
                            fontFamily = X14uFont,
                            fontSize = dpText(13f),
                            color = engraved,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Two short lines for a card: the first two groups it holds, look and film first. */
private fun presetCardLines(item: PresetItem): List<String> =
    listOf(PresetGroup.Look, PresetGroup.Film, PresetGroup.Exposure, PresetGroup.Processing, PresetGroup.WhiteBalance, PresetGroup.Lens)
        .filter { it in item.groups }
        .map { presetGroupText(it, item.camera, item.summary) }
        .filter { it != "—" }
        .take(2)

@Composable
internal fun NameField(value: String, onValue: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(CapturePreset.MAX_NAME)) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontFamily = X14uFont, fontSize = dpText(14f)),
        cursorBrush = SolidColor(CaptureColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .background(X14uPanelStyle.Track, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag("x14u_preset_name")
    )
}

/** What a group holds now (save sheet) or in a preset (details). */
internal fun presetGroupText(group: PresetGroup, camera: PresetCamera, summary: Map<PresetGroup, String>): String =
    when (group) {
        PresetGroup.Exposure -> listOfNotNull(camera.exposure?.let { presetExposureText(it) }, summary[group])
            .joinToString(" · ")
        PresetGroup.WhiteBalance -> camera.whiteBalance?.let { wb ->
            if (wb.mode == com.rawr.camera.model.WhiteBalanceMode.ManualTempTint.name)
                "${wb.kelvin} K" + if (wb.tint != 0) " · tint ${wb.tint}" else ""
            else com.rawr.camera.model.WhiteBalanceMode.entries.firstOrNull { it.name == wb.mode }?.label ?: wb.mode
        }.orEmpty()
        PresetGroup.Lens -> camera.lens?.let { lens ->
            val factor = com.rawr.camera.settings.model.ZoomChain.factorOf(lens.lensId)
            val mm = factor?.let {
                val z = if (lens.zoom > 0f) lens.zoom else it
                com.rawr.camera.settings.model.LensButtons.mmOf(z * lens.videoCrop.coerceAtLeast(1f))
            }
            listOfNotNull(lens.lensId, mm?.let { "$it mm" }, lens.aperture?.removePrefix("f/")?.let { "F$it" })
                .joinToString(" · ")
        }.orEmpty()
        else -> summary[group].orEmpty()
    }.ifEmpty { "—" }

private fun presetExposureText(e: com.rawr.camera.settings.model.PresetExposure): String =
    e.label.ifEmpty { com.rawr.camera.model.ExposureMode.entries.firstOrNull { it.name == e.mode }?.shortLabel ?: e.mode }

/**
 * Save the current settings as a new preset: its name and the groups it holds (exposure, white balance, lens and
 * zoom, look, film recipe, merge and processing) as tiles. Groups left out stay as they are when it is applied.
 */
@Composable
internal fun X14uPresetSaveSheet(
    label: String,
    current: PresetCamera,
    summary: Map<PresetGroup, String>,
    onSave: (name: String, groups: Set<PresetGroup>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var groups by remember {
        mutableStateOf(setOf(PresetGroup.Exposure, PresetGroup.Lens, PresetGroup.Look, PresetGroup.Film))
    }
    val all = PresetGroup.entries
    val save = { if (groups.isNotEmpty()) onSave(name, groups) }
    X14uSheet("SAVE PRESET", onClose, modifier.testTag("x14u_preset_save_sheet")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = PresetMono, fontSize = dpText(14f), color = CaptureColors.Accent)
            Spacer(Modifier.width(10.dp))
            NameField(name, { name = it }, onDone = save, modifier = Modifier.weight(1f))
        }
        X14uSheetLabel("Includes")
        X14uTiles(
            count = all.size, columns = 2,
            isOn = { all[it] in groups },
            title = { all[it].label },
            subtitle = { presetGroupText(all[it], current, summary) },
            onTap = { i -> groups = if (all[i] in groups) groups - all[i] else groups + all[i] }
        )
        X14uSheetDivider()
        X14uSegments(
            labels = listOf("Cancel", "Save"), selected = null, emphasis = 1,
            enabled = { it == 0 || groups.isNotEmpty() },
            onSelect = { if (it == 0) onClose() else save() }
        )
    }
}

/**
 * A preset's details (tap the active card): what it holds as tiles (left-out groups dim), then Update (the current
 * settings into it), Rename, Duplicate and Delete; P0 offers "App defaults" instead of Delete. Delete and App
 * defaults ask once more.
 */
@Composable
internal fun X14uPresetDetailsSheet(
    item: PresetItem,
    /** The preset in use: "Update" saves the live settings into it; any other offers "Replace" (asked first). */
    active: Boolean,
    full: Boolean,
    onUpdate: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renaming by remember(item.id) { mutableStateOf(false) }
    var name by remember(item.id) { mutableStateOf(item.name) }
    var confirm by remember(item.id) { mutableStateOf(false) }
    var confirmReplace by remember(item.id) { mutableStateOf(false) }
    val all = PresetGroup.entries
    X14uSheet(item.name, onClose, modifier.testTag("x14u_preset_details"), lead = item.label) {
        if (renaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NameField(name, { name = it }, onDone = { onRename(name); renaming = false }, modifier = Modifier.weight(1f))
            }
        }
        X14uTiles(
            count = all.size, columns = 2,
            isOn = { false },
            title = { all[it].label },
            subtitle = { if (all[it] in item.groups) presetGroupText(all[it], item.camera, item.summary) else "not included" },
            onTap = null,
            dim = { all[it] !in item.groups }
        )
        X14uSheetDivider()
        when {
            renaming -> X14uSegments(listOf("Cancel", "Done"), selected = null, emphasis = 1, onSelect = {
                if (it == 1) onRename(name) else name = item.name
                renaming = false
            })
            confirmReplace -> {
                X14uSheetLabel("Replace ${item.label} ${item.name} with the current settings?")
                X14uSegments(listOf("Cancel", "Replace"), selected = null, emphasis = 1, onSelect = {
                    if (it == 1) onUpdate()
                    confirmReplace = false
                })
            }
            confirm -> {
                X14uSheetLabel(if (item.isBaseline) "Put P0 back to the app's defaults?" else "Delete ${item.label} ${item.name}?")
                X14uSegments(listOf("Cancel", if (item.isBaseline) "App defaults" else "Delete"), selected = null,
                    emphasis = 1, onSelect = {
                        if (it == 1) { if (item.isBaseline) onRestoreDefaults() else onDelete() }
                        confirm = false
                    })
            }
            else -> X14uSegments(
                labels = listOf(if (active) "Update" else "Replace", "Rename", "Duplicate", if (item.isBaseline) "Defaults" else "Delete"),
                selected = null, emphasis = 0,
                enabled = { it != 2 || !full },
                onSelect = { i ->
                    when (i) {
                        0 -> if (active) onUpdate() else confirmReplace = true
                        1 -> renaming = true
                        2 -> onDuplicate()
                        else -> confirm = true
                    }
                }
            )
        }
    }
}
