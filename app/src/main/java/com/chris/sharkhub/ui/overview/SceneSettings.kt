package com.chris.sharkhub.ui.overview

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ui.ActionChip
import com.chris.sharkhub.ui.RoundIconButton
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.SegmentedControl
import com.chris.sharkhub.ui.overview.live.LiveSupport
import com.chris.sharkhub.ui.theme.LocalStyle
import kotlinx.coroutines.delay

/** What the scene sheet edits — mirrored to Prefs by the screens (paintColour, sceneLighting, sceneMotion). */
data class SceneSettings(
    val paint: Int = Prefs.DEFAULT_PAINT,
    /** A [SceneLighting] id: "auto" (Dynamic) or a fixed time of day. */
    val lighting: String = SceneLighting.AUTO,
    val motion: Boolean = true,
    /** The live Filament truck rather than the pre-rendered plates, where the build has its assets. */
    val live: Boolean = true,
    /** The plates truck's lit-lamp overlays, and its accent scan line. */
    val lamps: Boolean = true,
    val sweep: Boolean = true,
) {
    companion object {
        fun from(prefs: Prefs) = SceneSettings(prefs.paintColour, prefs.sceneLighting, prefs.sceneMotion, prefs.liveScene,
            lamps = prefs.sceneLamps, sweep = prefs.sceneSweep)
    }
}

/** Store the sheet's values (the screens' side of [SceneSettings.from]). */
fun Prefs.save(s: SceneSettings) {
    paintColour = s.paint; sceneLighting = s.lighting; sceneMotion = s.motion; liveScene = s.live
    sceneLamps = s.lamps; sceneSweep = s.sweep
}

/** One row of the scene sheet: its title, an optional line under it, and the control that edits the settings. Add rows here. */
class SceneSettingRow(val title: String, val subtitle: String? = null, val control: @Composable (SceneSettings, (SceneSettings) -> Unit) -> Unit)

val sceneSettingRows: List<SceneSettingRow> = listOf(
    SceneSettingRow("Car colour") { s, set -> PaintPicker(s.paint) { set(s.copy(paint = it)) } },
    SceneSettingRow("Time of day") { s, set ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Dawn is something Dynamic produces, not a choice; an old "dawn" pref shows nothing selected but still works
            SegmentedControl(SceneLighting.choiceLabels, SceneLighting.choiceIds.indexOf(s.lighting)) { set(s.copy(lighting = SceneLighting.choiceIds[it])) }
            if (s.lighting == SceneLighting.AUTO) LocationHint()
        }
    },
    SceneSettingRow("Scene motion", "On moves the road lines, posts, wheels and backdrop with speed · Off = still scene") { s, set ->
        SegmentedControl(listOf("Off", "On"), if (s.motion) 1 else 0) { set(s.copy(motion = it == 1)) }
    },
    SceneSettingRow("Truck", "Live 3D orbits with two fingers · Plates are the pre-rendered scene") { s, set ->
        // the live renderer needs the private asset pack and Filament's native libraries (never the JVM)
        val ctx = LocalContext.current
        val liveOk = remember { LiveSupport.available(ctx) }
        if (liveOk) SegmentedControl(listOf("Plates", "Live 3D"), if (s.live) 1 else 0) { set(s.copy(live = it == 1)) }
        else Text("Live 3D needs the private asset pack in the build", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    // the plates truck's lamp overlays can be switched off, to see whether they are what flashes
    SceneSettingRow("Lamps on the truck") { s, set -> SegmentedControl(listOf("Off", "On"), if (s.lamps) 1 else 0) { set(s.copy(lamps = it == 1)) } },
    SceneSettingRow("Scan line") { s, set -> SegmentedControl(listOf("Off", "On"), if (s.sweep) 1 else 0) { set(s.copy(sweep = it == 1)) } },
)

/**
 * Dynamic needs to know where the unit is for a real sunrise; without a fix it guesses fixed hours
 * (the unit showed night at 05:35 with sunrise at 05:52). The location permission is otherwise only
 * asked for on the Bluetooth page, so offer it here; the line goes once a provider has a fix.
 */
@Composable
private fun LocationHint() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var fix by remember { mutableStateOf(lastKnownLocation(ctx) != null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { fix = lastKnownLocation(ctx) != null }
    // a fresh grant may take a provider a while to produce one
    LaunchedEffect(fix) { while (!fix) { delay(5_000); fix = lastKnownLocation(ctx) != null } }
    if (fix) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No location fix — sunrise and sunset are guessed", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        ActionChip("Use location", Icons.Rounded.MyLocation) { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }
}

/**
 * A small frosted button floating over the scene — a 38 dp disc for an icon alone, a pill when it
 * carries a [label]. The back, Rage Mode and cog buttons on the fullscreen Vehicle page all use it.
 */
@Composable
fun FrostedButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  label: String? = null, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(38.dp).clip(CircleShape).background(cs.surface.copy(alpha = 0.56f))
            .border(1.dp, cs.onSurface.copy(alpha = 0.16f), CircleShape).clickable(onClick = onClick)
            .padding(horizontal = if (label == null) 9.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

/** The small frosted cog that opens the scene sheet, for a corner of the scene panel. */
@Composable
fun SceneSettingsCog(onClick: () -> Unit, modifier: Modifier = Modifier) = FrostedButton(Icons.Rounded.Settings, "Scene settings", onClick, modifier)

/**
 * The scene sheet: a compact glass popover in the corner of the scene panel with the rows of
 * [sceneSettingRows]. Tapping anywhere outside it (the whole panel becomes a scrim) closes it, so
 * it only ever covers the corner it sits in. Call it inside the panel's Box, last, so it's on top.
 */
@Composable
fun BoxScope.SceneSettingsSheet(settings: SceneSettings, onSettings: (SceneSettings) -> Unit, onDismiss: () -> Unit, align: Alignment, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalStyle.current.panelRadius)
    // the scrim is the whole panel; it also tells the sheet how tall it may be
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        val scroll = rememberScrollState()
        Column(
            modifier.align(align).width(380.dp).heightIn(max = maxHeight - 24.dp).clip(shape).background(cs.surface.copy(alpha = 0.88f))
                .border(1.dp, cs.onSurface.copy(alpha = 0.16f), shape)
                .pointerInput(Unit) { detectTapGestures { } }        // taps on the sheet stay on the sheet
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // the header stays put; the rows scroll under it with a fade while there's more below
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Scene", color = cs.primary)
                Spacer(Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Close, "Close", size = 34.dp, onClick = onDismiss)
            }
            Box(Modifier.weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(scroll).padding(end = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sceneSettingRows.forEach { row ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                            row.control(settings, onSettings)
                        }
                    }
                }
                if (scroll.canScrollForward) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, cs.surface.copy(alpha = 0.95f))))
                )
            }
        }
    }
}

/** The paint swatches with a custom hex under them; the chosen one is ringed and ticked. */
@Composable
private fun PaintPicker(paint: Int, onPaint: (Int) -> Unit) {
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaintColours.swatches.forEach { s -> PaintSwatchDot(s, s.argb == paint) { custom = ""; onPaint(s.argb) } }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { v -> custom = v; PaintColours.parseHex(v)?.let(onPaint) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (PaintColours.swatches.none { it.argb == paint }) "Custom ${PaintColours.hex(paint)}" else "Custom #RRGGBB") },
            textStyle = MaterialTheme.typography.bodyMedium,
            isError = custom.isNotBlank() && PaintColours.parseHex(custom) == null,
        )
    }
}

/** A disc of the paint itself — the one place a colour that isn't the theme's belongs on screen — ringed and ticked when chosen. */
@Composable
private fun PaintSwatchDot(swatch: PaintSwatch, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color(swatch.argb))
            .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, swatch.name, tint = if (Color(swatch.argb).luminance() > 0.5f) cs.background else cs.onSurface,
            modifier = Modifier.size(18.dp))
    }
}
