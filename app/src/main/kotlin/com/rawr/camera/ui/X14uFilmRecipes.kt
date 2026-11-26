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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawr.camera.settings.model.FilmPreset
import com.rawr.camera.settings.model.FilmStocks

/**
 * X14U (Nov 26): film recipes as a strip of cards, like the presets: the FILM plate next to the preset plate opens it
 * (photo with film on). A card holds its number and name only; ··· opens what it holds (film, print, exposure, format)
 * and its actions. Factory and saved recipes share one order (long-press a card and drag). Picking or changing a
 * recipe doesn't mark the P preset changed (film is optional in a preset).
 */
data class FilmRecipeItem(
    val id: String,
    /** "F1", "F2"… by position. */
    val label: String,
    val name: String,
    /** Saved by the user (factory recipes can't be renamed, updated or deleted). */
    val user: Boolean,
    val film: String,
    val print: String,
    val exposure: String,
    val format: String
)

data class FilmRecipesUi(
    val items: List<FilmRecipeItem> = emptyList(),
    val activeId: String? = null,
    /** The live film settings differ from the active recipe. */
    val modified: Boolean = false
) {
    val active: FilmRecipeItem? get() = items.firstOrNull { it.id == activeId }
}

interface FilmRecipeActions {
    fun apply(id: String) {}
    /** The live film settings as a new recipe (it becomes the active one). */
    fun save(name: String) {}
    /** A copy of recipe [id] (its own settings) as a new saved recipe. */
    fun duplicate(id: String) {}
    /** The live film settings into saved recipe [id]. */
    fun update(id: String) {}
    /** The active recipe's own settings back. */
    fun revert() {}
    fun rename(id: String, name: String) {}
    fun delete(id: String) {}
    fun move(from: Int, to: Int) {}
    /** The recipe editor opens: remember the live film settings (Cancel puts them back); [id] = edit that recipe. */
    fun beginEdit(id: String?) {}
    /** Editor cancelled: the film settings from before it opened. */
    fun cancelEdit() {}
    /** Editor saved: forget what was remembered. */
    fun finishEdit() {}

    companion object {
        val None = object : FilmRecipeActions {}
    }
}

/** Cards for [presets] (already in strip order). */
fun filmRecipeItems(presets: List<FilmPreset>, isUser: (String) -> Boolean): List<FilmRecipeItem> =
    presets.mapIndexed { i, p ->
        val look = p.look
        val reversal = look.film in FilmStocks.positiveFilmIndices
        val ev = listOf(look.filmExposureEv, look.printExposureEv).let { (f, pr) ->
            listOfNotNull(
                f.takeIf { kotlin.math.abs(it) >= 0.005f }?.let { "film ${signedEv(it)}" },
                pr.takeIf { kotlin.math.abs(it) >= 0.005f }?.let { "print ${signedEv(it)}" }
            ).joinToString(" · ").ifEmpty { "0 EV" }
        }
        FilmRecipeItem(
            id = p.id,
            label = "F${i + 1}",
            name = p.name,
            user = isUser(p.id),
            film = FilmStocks.films.getOrElse(look.film) { "?" },
            print = when {
                reversal -> "Slide (no print)"
                look.process != 0 -> FilmStocks.processes.getOrElse(look.process) { "?" }
                else -> FilmStocks.papers.getOrElse(look.paper) { "?" }
            },
            exposure = ev,
            format = FilmStocks.filmFormats.getOrElse(look.filmFormat) { "?" }
        )
    }

private fun signedEv(v: Float): String {
    val r = kotlin.math.round(v * 10) / 10f
    return (if (r >= 0) "+" else "") + r + " EV"
}

private val FilmMono = FontFamily.Monospace

/** The strip: header ("Film recipes", or "F2 changed · Update · Revert" and "+") over the cards. */
@Composable
internal fun X14uFilmRecipeStrip(
    recipes: FilmRecipesUi,
    enabled: Boolean,
    onApply: (FilmRecipeItem) -> Unit,
    onDetails: (FilmRecipeItem) -> Unit,
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
    val order = remember(recipes.items.map { it.id }) { recipes.items.map { it.id }.toMutableStateList() }
    val reorderScope = rememberCoroutineScope()
    val reorder = remember(stepPx) { RowReorder<String>(stepPx, reorderScope, minIndex = 0) }
    val dragId = reorder.dragging
    val items by rememberUpdatedState(recipes.items)
    val activeNow by rememberUpdatedState(recipes.activeId)
    val applyNow by rememberUpdatedState(onApply)
    val detailsNow by rememberUpdatedState(onDetails)
    val moveNow by rememberUpdatedState(onMove)
    val engraved = if (isClassicStyle()) ClassicColors.Engraved else Color.White
    Column(Modifier.fillMaxSize().testTag("x14u_film_strip")) {
        val active = recipes.active
        Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp)) {
            if (recipes.modified && active != null) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Text("${active.label} changed", fontFamily = X14uFont, fontSize = dpText(13f), color = engraved.copy(alpha = .75f))
                    Spacer(Modifier.width(14.dp))
                    // A factory recipe can't be updated: its changes go to a new one (+).
                    if (active.user) {
                        Text("Update", fontFamily = X14uFont, fontSize = dpText(13.5f), color = accent,
                            modifier = Modifier.clickable(enabled = enabled) { haptics.selection(); onUpdate() }.padding(horizontal = 4.dp, vertical = 6.dp))
                        Text(" · ", fontFamily = X14uFont, fontSize = dpText(13f), color = Color.White.copy(alpha = .4f))
                    }
                    Text("Revert", fontFamily = X14uFont, fontSize = dpText(13.5f), color = engraved.copy(alpha = .9f),
                        modifier = Modifier.clickable(enabled = enabled) { haptics.selection(); onRevert() }.padding(horizontal = 4.dp, vertical = 6.dp))
                }
            } else {
                Text("Film recipes", fontFamily = X14uFont, fontSize = dpText(15f), color = engraved,
                    modifier = Modifier.align(Alignment.Center))
            }
            Box(
                Modifier.align(Alignment.CenterEnd).size(40.dp, 36.dp)
                    .clickable(enabled = enabled) { haptics.selection(); onSaveNew() }
                    .testTag("x14u_film_save")
                    .semantics { contentDescription = "Save the current film settings as a new recipe" },
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
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // key() straight in the loop (see X14uPresetStrip): a keyed card must be able to move between places.
            order.mapNotNull { id -> recipes.items.firstOrNull { it.id == id } }.forEach { item ->
                val id = item.id
                key(id) {
                    val selected = id == recipes.activeId
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
                                    enabled = enabled,
                                    order = order,
                                    onStart = { haptics.selection() },
                                    onMove = { from, to ->
                                        order.add(to, order.removeAt(from))
                                        haptics.selection()
                                    },
                                    onEnd = {
                                        val from = items.indexOfFirst { it.id == id }
                                        val to = order.indexOf(id)
                                        if (from >= 0 && to >= 0 && from != to) moveNow(from, to)
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
                        Text(
                            "F${order.indexOf(id) + 1}",
                            fontFamily = FilmMono,
                            fontSize = dpText(12.5f),
                            color = if (selected) accent else engraved.copy(alpha = .85f),
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(y = (-6).dp).size(34.dp, 28.dp)
                                .clickable(enabled = enabled) { haptics.selection(); detailsNow(item) }
                                .semantics { contentDescription = "${item.label} ${item.name} details" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("···", fontSize = dpText(13f), color = engraved.copy(alpha = .7f))
                        }
                        // The name only (up to two lines): what a recipe holds is in its details.
                        Text(
                            item.name,
                            fontFamily = X14uFont,
                            fontSize = dpText(13f),
                            lineHeight = dpText(16f),
                            color = engraved,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Save the live film settings as a new recipe: just its name. */
@Composable
internal fun X14uFilmRecipeSaveSheet(
    label: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    val save = { if (name.isNotBlank()) onSave(name) }
    X14uSheet("SAVE FILM RECIPE", onClose, modifier.testTag("x14u_film_save_sheet")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = FilmMono, fontSize = dpText(14f), color = CaptureColors.Accent)
            Spacer(Modifier.width(10.dp))
            NameField(name, { name = it }, onDone = save, modifier = Modifier.weight(1f))
        }
        X14uSheetDivider()
        X14uSegments(
            labels = listOf("Cancel", "Save"), selected = null, emphasis = 1,
            enabled = { it == 0 || name.isNotBlank() },
            onSelect = { if (it == 0) onClose() else save() }
        )
    }
}

/**
 * A recipe's details (··· or a tap on the active card): film, print, exposure and format as tiles, then Edit (opens
 * the recipe editor on it), Rename and Delete (saved only; Delete asked once more) and Duplicate.
 */
@Composable
internal fun X14uFilmRecipeDetailsSheet(
    item: FilmRecipeItem,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renaming by remember(item.id) { mutableStateOf(false) }
    var name by remember(item.id) { mutableStateOf(item.name) }
    var confirm by remember(item.id) { mutableStateOf(false) }
    val rows = listOf("Film" to item.film, "Print" to item.print, "Exposure" to item.exposure, "Format" to item.format)
    X14uSheet(item.name, onClose, modifier.testTag("x14u_film_details"), lead = item.label) {
        if (renaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NameField(name, { name = it }, onDone = { onRename(name); renaming = false }, modifier = Modifier.weight(1f))
            }
        }
        X14uTiles(
            count = rows.size, columns = 2,
            isOn = { false },
            title = { rows[it].first },
            subtitle = { rows[it].second },
            onTap = null
        )
        if (!item.user) X14uSheetLabel("Built-in recipe: Duplicate makes an editable copy.")
        X14uSheetDivider()
        when {
            renaming -> X14uSegments(listOf("Cancel", "Done"), selected = null, emphasis = 1, onSelect = {
                if (it == 1) onRename(name) else name = item.name
                renaming = false
            })
            confirm -> {
                X14uSheetLabel("Delete ${item.label} ${item.name}?")
                X14uSegments(listOf("Cancel", "Delete"), selected = null, emphasis = 1, onSelect = {
                    if (it == 1) onDelete()
                    confirm = false
                })
            }
            else -> X14uSegments(
                labels = listOf("Edit", "Rename", "Duplicate", "Delete"),
                selected = null, emphasis = null,
                enabled = { i -> i == 2 || item.user },
                onSelect = { i ->
                    when (i) {
                        0 -> onEdit()
                        1 -> renaming = true
                        2 -> onDuplicate()
                        else -> confirm = true
                    }
                }
            )
        }
    }
}

/**
 * The recipe editor's header (over the film cells): Cancel, what is edited ("New recipe" or "F3 · Name"), and Save
 * (new) or Update (a saved recipe).
 */
@Composable
internal fun X14uFilmEditHeader(
    title: String,
    saveLabel: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .padding(horizontal = 10.dp)
            .background(X14uPanelStyle.Panel, RoundedCornerShape(12.dp))
            .testTag("x14u_film_edit_header")
    ) {
        Text("Cancel", fontFamily = X14uFont, fontSize = dpText(13.5f), color = Color.White.copy(alpha = .75f),
            modifier = Modifier.align(Alignment.CenterStart).clickable { haptics.selection(); onCancel() }
                .padding(horizontal = 12.dp, vertical = 8.dp))
        Text(title, fontFamily = X14uFont, fontSize = dpText(14f), color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 80.dp))
        Text(saveLabel, fontFamily = X14uFont, fontSize = dpText(13.5f), color = CaptureColors.Accent,
            modifier = Modifier.align(Alignment.CenterEnd).clickable { haptics.selection(); onSave() }
                .padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
