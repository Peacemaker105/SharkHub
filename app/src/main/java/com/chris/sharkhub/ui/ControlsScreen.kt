package com.chris.sharkhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.CommandResult
import com.chris.sharkhub.car.ControlGroup
import com.chris.sharkhub.car.SelectorOption
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.VehicleControls
import com.chris.sharkhub.car.VehicleSelector
import com.chris.sharkhub.car.VehicleToggle
import com.chris.sharkhub.data.Prefs

/**
 * Vehicle screen: the driving modes, and every toggle in [VehicleControls] with a switch showing
 * what the car currently reports. Each toggle also has a small power icon: arm it and Shark Hub
 * switches that function OFF every time it connects to the car (Chris's "auto-off when the app
 * loads"). Risky items (ACC, ESP, the modes) ask before sending and are never auto-applied.
 */
@Composable
fun ControlsScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { Prefs(ctx) }
    // Keeps the poll (and so the readback) running while this screen is open.
    remember(car) { car.telemetry(periodMs = 1500) }.collectAsStateWithLifecycle(Telemetry())
    val vehicle by car.vehicle.collectAsStateWithLifecycle()
    val discovery by car.discovery.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<CommandResult?>(null) }
    var autoOff by remember { mutableStateOf(prefs.autoOffControls) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmText by remember { mutableStateOf("") }
    LaunchedEffect(car) { car.commandResults.collect { last = it } }
    val connected = discovery?.backend != null

    val subtitle = when {
        discovery == null -> "Looking for the car…"
        !connected -> "Preview — car service offline, nothing is sent to the car"
        last == null -> "Switches show what the car reports; — means it isn't reporting (ADAS sleeps while parked)"
        last!!.result.isSuccess -> "✓ ${last!!.label}"
        else -> "✗ ${last!!.label}: ${last!!.result.exceptionOrNull()?.message}"
    }

    fun ask(text: String, action: () -> Unit) { confirmText = text; confirm = action }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Vehicle", nav, subtitle = subtitle) {
            when {
                discovery == null -> StatusChip("Connecting…", cs.onSurfaceVariant)
                connected -> StatusChip("Connected", cs.primary)
                else -> StatusChip("Preview", cs.secondary)
            }
        }
        val toggleRow: @Composable (VehicleToggle) -> Unit = { t ->
            ToggleRow(
                t = t,
                state = car.isOn(t),
                fromLastSet = car.isFromLastSet(t),
                enabled = connected,
                armed = t.id in autoOff,
                onArm = { armed ->
                    autoOff = if (armed) autoOff + t.id else autoOff - t.id
                    prefs.autoOffControls = autoOff
                },
                onChange = { on ->
                    if (t.risky) ask("Turn ${t.label} ${if (on) "on" else "off"}? This changes how the car drives.") { car.setToggle(t, on) }
                    else car.setToggle(t, on)
                },
            )
        }
        val modes: @Composable () -> Unit = {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("Driving modes")
                    VehicleControls.selectors.forEach { s ->
                        SelectorRow(s, current = s.optionFor(vehicle[s.id]), enabled = connected) { o ->
                            ask("Switch ${s.label} to ${o.label}?") { car.setSelector(s, o) }
                        }
                    }
                }
            }
        }
        val group: @Composable (ControlGroup) -> Unit = { g ->
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    SectionLabel(g.label)
                    Spacer(Modifier.height(6.dp))
                    VehicleControls.toggles.filter { it.group == g }.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                        toggleRow(t)
                    }
                }
            }
        }
        val note: @Composable () -> Unit = {
            Text(
                "Power icon = switch this off every time Shark Hub connects. Items marked risky " +
                    "always ask first and are never auto-applied.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        val area = Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        if (isPortrait()) {
            // One long list: two half-height scrollers would each cut their group off.
            LazyColumn(area, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { modes() }
                item { group(ControlGroup.QUICK) }
                item { group(ControlGroup.LIGHTS) }
                item { group(ControlGroup.ADAS) }
                item { note() }
            }
        } else {
            Split(area, first = { pane ->
                LazyColumn(pane, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { modes() }
                    item { group(ControlGroup.QUICK) }
                    item { group(ControlGroup.LIGHTS) }
                }
            }) { pane ->
                LazyColumn(pane, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { group(ControlGroup.ADAS) }
                    item { note() }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Send to the car?") },
            text = { Text(confirmText) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(
    t: VehicleToggle,
    state: Boolean?,
    fromLastSet: Boolean,
    enabled: Boolean,
    armed: Boolean,
    onArm: (Boolean) -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                if (t.risky) StatusChip("risky", cs.error)
            }
            Text(
                when {
                    state == null -> t.hint?.let { "$it · not reported" } ?: "not reported"
                    fromLastSet -> (t.hint?.let { "$it · " } ?: "") + (if (state) "on" else "off") + " · last set here; car reports while driving"
                    else -> t.hint ?: (if (state) "on" else "off")
                },
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
        }
        if (!t.risky) {
            IconToggleButton(checked = armed, onCheckedChange = onArm) {
                Icon(Icons.Rounded.PowerSettingsNew, if (armed) "Auto-off armed" else "Arm auto-off",
                    tint = if (armed) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
        Switch(checked = state == true, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectorRow(s: VehicleSelector, current: SelectorOption?, enabled: Boolean, onSelect: (SelectorOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(current?.label ?: "—", style = MaterialTheme.typography.bodyMedium, color = cs.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.options.forEach { o ->
                val selected = o == current
                Text(
                    o.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) cs.onPrimary else cs.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surfaceVariant)
                        .border(1.dp, if (selected) cs.primary else cs.outlineVariant, CircleShape)
                        .clickable(enabled = enabled && !selected) { onSelect(o) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        s.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
    }
}
